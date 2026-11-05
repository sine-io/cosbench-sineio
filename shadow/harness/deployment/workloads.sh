#!/usr/bin/env bash
set -Eeuo pipefail

scenario=${1:?Expected scenario name}
driver_count=${2:?Expected driver count}
report="/reports/deployment-integration/$scenario"
runtime=$(mktemp -d /tmp/cosbench-deployment-workloads.XXXXXX)
trap 'rm -rf -- "$runtime"' EXIT
mkdir -p "$report"
controller=http://controller:19088/controller
cli="$controller/cli"
credentials='username=anonymous&password=cosbench'
endpoint=${COSBENCH_MINIO_ENDPOINT:?}
access_key=$(<"$COSBENCH_MINIO_ACCESS_KEY_FILE")
secret_key=$(<"$COSBENCH_MINIO_SECRET_KEY_FILE")

curl -fsS --max-time 5 "$cli/index.action?$credentials" > "$report/cli-index.txt"
grep -q "Total: $driver_count drivers" "$report/cli-index.txt"
printf 'factory\tworkload\twrite\tread\tlist\thead\tmwrite\tdelete\tsuccess_ratio\tclose\n' \
    > "$report/providers.tsv"

for factory in sio siov1 gdas s3; do
    prefix="cb19-$factory-$(date +%s%N)-"
    storage="endpoint=$endpoint;accesskey=$access_key;secretkey=$secret_key;timeout=3000;path_style_access=true"
    [[ $factory == s3 ]] || storage+=';aws_region=us-east-1;max_connections=4;part_size=5242880'
    storage=$(printf '%s' "$storage" | sed 's/&/\&amp;/g; s/"/\&quot;/g; s/</\&lt;/g')
    workload="$runtime/$factory.xml"
    cat > "$workload" <<XML
<workload name="integration-$scenario-$factory">
  <auth type="none"/>
  <storage type="$factory" config="$storage"/>
  <workflow>
    <workstage name="init"><work type="init" workers="1" interval="1" config="cprefix=$prefix;containers=r(1,1)"/></workstage>
XML
    for operation in write read list head mwrite read delete delete; do
        object_prefix=obj
        size=64
        case "$operation" in mwrite) object_prefix=multi; size=5242917 ;; esac
        # Read and delete the multipart object in their second occurrence.
        if [[ $operation == read && ${multipart_written:-0} == 1 \
                || $operation == delete && ${ordinary_deleted:-0} == 1 ]]; then
            object_prefix=multi
        fi
        cat >> "$workload" <<XML
    <workstage name="$operation-$object_prefix">
      <work name="$operation" workers="2" totalOps="2" interval="1">
        <operation type="$operation" ratio="100" config="cprefix=$prefix;containers=c(1);oprefix=$object_prefix;objects=c(1);sizes=c(${size})B;content=zero"/>
      </work>
    </workstage>
XML
        [[ $operation != mwrite ]] || multipart_written=1
        [[ $operation != delete ]] || ordinary_deleted=1
    done
    unset multipart_written ordinary_deleted
    cat >> "$workload" <<XML
    <workstage name="dispose"><work type="dispose" workers="1" interval="1" config="cprefix=$prefix;containers=r(1,1)"/></workstage>
  </workflow>
</workload>
XML
    # Keep the exact fixture shape without exporting storage credentials.
    sed -E 's/(accesskey|secretkey)=[^;"<]*/\1=<REDACTED>/g' "$workload" > "$report/$factory-workload.xml"
    curl -fsS --max-time 10 -F "config=@$workload;type=application/xml" \
        "$cli/submit.action?$credentials" > "$report/$factory-submit.txt"
    id=$(sed -nE 's/^Accepted with ID: (w[0-9]+).*$/\1/p' "$report/$factory-submit.txt")
    [[ -n $id ]] || { cat "$report/$factory-submit.txt" >&2; exit 1; }
    finished=0
    deadline=$((SECONDS + 180))
    while ((SECONDS < deadline)); do
        curl -fsS --max-time 5 "$cli/workload.action?$credentials&id=$id" > "$report/$factory-state.txt"
        if grep -q 'FINISHED' "$report/$factory-state.txt"; then finished=1; break; fi
        if grep -Eq 'FAILED|TERMINATED|CANCELLED' "$report/$factory-state.txt"; then
            cat "$report/$factory-state.txt" >&2
            exit 1
        fi
        sleep 0.2
    done
    [[ $finished == 1 ]]
    curl -fsS --max-time 5 "$controller/prometheus" > "$report/$factory-prometheus.txt"
    for operation in write read list head mwrite delete; do
        count=2
        case "$operation" in read|delete) count=4 ;; esac
        awk -v workload="workload=\"$id (integration-$scenario-$factory)\"" \
            -v operation="operation_type=\"$operation\"" -v expected="$count" '
            index($0, workload) && index($0, operation) && /^cosbench_workload_ops_count / { count += $NF; samples++ }
            index($0, workload) && index($0, operation) && /^cosbench_workload_success_ratio / { if ($NF != 100) bad=1 }
            END { exit !(samples > 0 && count == expected && !bad) }
        ' "$report/$factory-prometheus.txt"
    done
    for operation in write read mwrite; do
        case "$operation" in write) bytes=128 ;; read) bytes=10485962 ;; mwrite) bytes=10485834 ;; esac
        awk -v workload="workload=\"$id (integration-$scenario-$factory)\"" \
            -v operation="operation_type=\"$operation\"" -v expected="$bytes" '
            index($0, workload) && index($0, operation) && /^cosbench_workload_byte_count / { bytes += $NF; samples++ }
            END { exit !(samples > 0 && bytes == expected) }
        ' "$report/$factory-prometheus.txt"
    done
    for driver in driver driver2; do
        [[ $driver != driver2 || $driver_count == 2 ]] || continue
        curl -fsS --max-time 5 --cacert "$COSBENCH_HTTP_TEST_CERT_FILE" \
            "https://$driver:18088/driver/health" > "$report/$factory-$driver-closed.json"
        grep -q '"activeMissions":0' "$report/$factory-$driver-closed.json"
    done
    printf '%s\t%s\t2\t4\t2\t2\t2\t4\t100\tno-active-missions\n' "$factory" "$id" >> "$report/providers.tsv"
done
printf 'status=passed\nclasspath=production-shared\nproviders=sio,siov1,gdas,s3\nworkload=init,write,read,list,head,mwrite,read,delete,dispose\n' \
    > "$report/workloads-summary.txt"
