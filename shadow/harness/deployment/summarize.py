#!/usr/bin/env python3
"""Index the actual commands and evidence for the integration acceptance IDs."""
import csv
import pathlib
import sys

reports = pathlib.Path(sys.argv[1])
run_status = int(sys.argv[2])
rows = []
missing = []
with pathlib.Path(__file__).with_name("acceptance.tsv").open() as source:
    for row in csv.DictReader(source, delimiter="\t"):
        absent = [name for name in row["Reports"].split(";")
                  if not (reports / name).is_file() or (reports / name).stat().st_size == 0]
        status = "incomplete" if absent else ("passed" if run_status == 0 else "recorded")
        rows.append((row["ID"], status, row["Command"], row["Reports"]))
        missing.extend(f"{row['ID']}: {name}" for name in absent)
with (reports / "integration-acceptance.tsv").open("w") as output:
    writer = csv.writer(output, delimiter="\t", lineterminator="\n")
    writer.writerow(("ID", "Status", "Command (shadow/harness/)", "Reports"))
    writer.writerows(rows)
(reports / "integration-summary.txt").write_text(
    f"status={'passed' if run_status == 0 and not missing else 'failed'}\n"
    f"run_status={run_status}\nacceptance_ids={len(rows)}\nmissing_reports={len(missing)}\n"
    + "".join(f"missing={name}\n" for name in missing)
)
sys.exit(1 if missing else 0)
