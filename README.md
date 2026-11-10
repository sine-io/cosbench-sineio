COSBench - Cloud Object Storage Benchmark
=========================================

COSBench is a benchmarking tool to measure the performance of Cloud Object Storage services. Object storage is an
emerging technology that is different from traditional file systems (e.g., NFS) or block device systems (e.g., iSCSI).
Amazon S3 and Openstack* swift are well-known object storage solutions.

The Java 21 migration candidate supports SineIO `sio`, `siov1`, `gdas`, and Amazon S3 `s3`. Controller and Driver run as separate processes using schema-1 YAML and the `bin/cosbench-controller`, `bin/cosbench-driver`, `bin/cosbench-stop`, and `bin/cosbench-config-migrate` commands. See [Java 21 build/development](BUILD-java21.md), [installation](shadow/packaging/templates/docs/production-install.md), [release notes](RELEASE-NOTES-java21.md), and [release acceptance/rollback](docs/release-rollback.md).

Other Providers (Swift, OpenIO, Amplidata, Scality, Ceph, CDMI, GCS, OSS, ECS and Manta) run only in the previous complete legacy distribution. Mock is test-only. Provider discovery is static: changing factories requires rebuilding the package and restarting Driver. Jackson 1.x, Castor and SDK major upgrades are deferred.

## Announcement

Because of [In Maintenance Mode as of July 31, 2024](https://github.com/aws/aws-sdk-java?tab=readme-ov-file#in-maintenance-mode-as-of-july-31-2024) and [End-of-Support on December 31, 2025](https://github.com/aws/aws-sdk-java?tab=readme-ov-file#end-of-support-on-december-31-2025), so the milestone version of cosbench-sineio (v0.6.0.0 released at 2024.8.31) changed as follows:

1. sio renamed to siov1, and will not be maintained.
2. siov2 renamed to sio, as a LTS Storage.


LTS
----------------------------------------
- The migration keeps the tested AWS SDK v1 `1.12.767` / v2 `2.26.8` closure. Further dependency upgrades are separate work.

COSBench Storages Comparision
----------------------------------------

The table below describes the legacy distribution. Current migrated behavior and supported operations are documented in the [SineIO](shadow/sineio-providers.md) and [S3](shadow/s3-provider.md) guides.

> 1. SineIO is S3 storage and hardware can be 'fullstack' (NVMe, SSD, HDD, Tape, Disc, etc.)
> 2. sio and siov1: Usage, please refer to conf/sio-config-sample.xml

|    Work/Operations/Features/SDK version    |  Storage type: s3  | Storage type: siov1  | Storage type: sio |
| :----------------------------------------: | :----------------: | :----------------: | :-----------------: |
|                    init                    | :heavy_check_mark: | :heavy_check_mark: | :heavy_check_mark:  |
|                  prepare                   | :heavy_check_mark: | :heavy_check_mark: | :heavy_check_mark:  |
|         :star:[New Work]: mprepare         |        :x:         | :heavy_check_mark: | :heavy_check_mark:  |
|                   write                    | :heavy_check_mark: | :heavy_check_mark: | :heavy_check_mark:  |
|       :star:[New Operation]: mwrite        |        :x:         | :heavy_check_mark: | :heavy_check_mark:  |
|                    read                    | :heavy_check_mark: | :heavy_check_mark: | :heavy_check_mark:  |
|                 filewrite                  | :heavy_check_mark: | :heavy_check_mark: | :heavy_check_mark:  |
|     :star:[New Operation]: mfilewrite      |        :x:         | :heavy_check_mark: | :heavy_check_mark:  |
|       :star:[New Operation]: restore       |        :x:         | :heavy_check_mark: | :heavy_check_mark:  |
|        :star:[New Operation]: head         |        :x:         | :heavy_check_mark: | :heavy_check_mark:  |
|                   delete                   | :heavy_check_mark: | :heavy_check_mark: | :heavy_check_mark:  |
|                  cleanup                   |     have :bug:     | :heavy_check_mark: | :heavy_check_mark:  |
|                  dispose                   |     have :bug:     | :heavy_check_mark: | :heavy_check_mark:  |
|                    list                    |     have :bug:     | :heavy_check_mark: | :heavy_check_mark:  |
|     :star:[New Feature]: no_verify_ssl     |        :x:         | :heavy_check_mark: | :heavy_check_mark:  |
|      :star:[New Feature]: aws_region       |      no need       |      no need       | :heavy_check_mark:  |
| :star:[New Feature]: s3-range-and-prefetch |        :x:         | :heavy_check_mark: |        TODO         |
|     :star:[New Feature]: GiB, MiB, KiB     |        :x:         | :heavy_check_mark: | :heavy_check_mark:  |
|     :star:[New Feature]: storage_class     |        :x:         | :heavy_check_mark: | :heavy_check_mark:  |
|     :star:[New Feature]: restore_days      |        :x:         | :heavy_check_mark: | :heavy_check_mark:  |
|     :star:[SDK version]: aws-sdk-java      |      1.10.76       |  :star2:1.12.767  |          -          |
|    :star:[SDK version]: aws-sdk-java-v2    |         -          |         -          |    :star2:2.26.8    |
|      :star:[New Feature]: localwrite       |        :x:         | :heavy_check_mark: | :heavy_check_mark:  |


Important Notice and Contact Information
----------------------------------------

a) COSBench is not a product, and it does not have a full-time support team. Before you use this tool, please understand 
the need to invest enough effort to learn how to use it effectively and to address possible bugs.

b) To help COSBench develop further, please become an active member of the community and consider giving back by making
contributions.

c) **Tutorial, writing in Chinese with 💖**: https://www.sineio.top/byte-of-cosbench/#/

d) Email: sinecelia.wang@gmail.com

e) WeChat/Twitter: SineCelia


Licensing
---------

a) Intel source code is being released under the Apache 2.0 license.

b) SineIO source code is being released under the MIT license.

c) Additional libraries used with COSBench have their own licensing; refer to 3rd-party-licenses.pdf for details.


Distribution Packages
---------------------

Build the Java 21 archive and separate production/development images using [BUILD-java21.md](BUILD-java21.md). The production archive includes executable applications, YAML, commands, supported Provider examples, licenses and checksums. The candidate is subject to [release acceptance](docs/release-rollback.md); final Scheme 3 acceptance remains open for the baseline inputs tracked by #01.

Previous SineIO binary distributions are published on the [GitHub Releases page](https://github.com/sine-io/cosbench-sineio/releases). Each legacy `*-sineio` release contains `cosbench-<version>-sineio.tar.gz` and its `.sha256` checksum. See [DISTRIBUTIONS.md](DISTRIBUTIONS.md) for legacy package details and [BUILD-sineio.md](BUILD-sineio.md) for their build procedure.

The historical `<version>-sineio` workflow and image instructions are retained under [`legacy/osgi/`](legacy/osgi/); they do not publish Java 21 candidate artifacts.


Installation & Usage
--------------------

For Java 21, use the [production installation guide](shadow/packaging/templates/docs/production-install.md) and [operator commands](shadow/operator-commands.md). The PDF and telnet/ncat requirements below apply to the legacy distribution.

> :star2:**Version more than or equal to  0.4.7.9: should install telnet**
>
> **Version less than 0.4.7.9: should install nmap-ncat**


Adaptor Development
-------------------
For Java 21, use the [Provider SPI guide](shadow/provider-spi.md). "COSBenchAdaptorDevGuide.pdf" describes legacy OSGi adapters.


Build
-----
Use [BUILD-java21.md](BUILD-java21.md) for Java 21 Docker builds and development. [BUILD.md](BUILD.md) and [BUILD-sineio.md](BUILD-sineio.md) retain the legacy Eclipse/Tycho instructions for rollback and older releases.


Resources
---------

Wiki: (https://github.com/sine-io/cosbench-sineio/wiki)

Issue tracking: (https://github.com/sine-io/cosbench-sineio/issues)

*Other names and brands may be claimed as the property of others.


Other related projects
----------------------
COSBench-Workload-Generator: (https://github.com/giteshnandre/COSBench-Workload-Generator)

COSBench-Plot: (https://github.com/icclab/cosbench-plot)

COSBench-Appliance: (https://susestudio.com/a/8Kp374/cosbench)

COSBench Ansible Playbook:

- (http://www.ksingh.co.in/blog/2016/05/29/deploy-cosbench-using-ansible/)
- (https://github.com/ksingh7/ansible-role-cosbench)
- (https://galaxy.ansible.com/ksingh7/cosbench/)


= END =
