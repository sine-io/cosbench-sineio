COSBench - Cloud Object Storage Benchmark
=========================================

COSBench is a benchmarking tool to measure the performance of Cloud Object Storage services. Object storage is an
emerging technology that is different from traditional file systems (e.g., NFS) or block device systems (e.g., iSCSI).
Amazon S3 and Openstack* swift are well-known object storage solutions.

COSBench now supports GDAS, SineIO*, OpenStack* Swift, Amazon* S3, OpenIO*, Amplidata v2.3, 2.5 and 3.1, Scality*, Ceph, CDMI, Google* Cloud Storage, Aliyun OSS as well as custom adaptors.


LTS
----------------------------------------
- AWS SDK version will be updated monthly for SineIO and GDAS if needed.

COSBench Storages Comparision
----------------------------------------

> 1. SineIO: hardware can be 'fullstack' (NVMe, SSD, HDD, Tape, Disc, etc.)
> 2. GDAS is S3 storage and hardware is Blu-ray Disc.
> 3. sio and siov2: Usage, please refer to conf/sio-config-sample.xml
> 4. gdas: Usage, please refer to conf/gdas-config-sample.xml

| Work/Operations/Features/SDK version |  Storage type: s3  | Storage type: sio  | Storage type: siov2 | Storage type: gdas |
| :----------------------------------: | :----------------: | :----------------: | :-----------------: | :----------------: |
|                 init                 | :heavy_check_mark: | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|               prepare                | :heavy_check_mark: | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|         [New Work]: mprepare         |        :x:         | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|                write                 | :heavy_check_mark: | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|       [New Operation]: mwrite        |        :x:         | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|                 read                 | :heavy_check_mark: | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|              filewrite               | :heavy_check_mark: | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|     [New Operation]: mfilewrite      |        :x:         | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|       [New Operation]: restore       |        :x:         | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|        [New Operation]: head         |        :x:         | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|                delete                | :heavy_check_mark: | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|               cleanup                |      not sure      | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|               dispose                |      not sure      | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|                 list                 |      not sure      | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|     [New Feature]: no_verify_ssl     |        :x:         | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|      [New Feature]: aws_region       |      no need       |      no need       | :heavy_check_mark:  |      no need       |
| [New Feature]: s3-range-and-prefetch |        :x:         | :heavy_check_mark: |        todo         |      no need       |
|     [New Feature]: GiB, MiB, KiB     |        :x:         | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|     [New Feature]: storage_class     |        :x:         | :heavy_check_mark: | :heavy_check_mark:  |      no need       |
|     [New Feature]: restore_days      |        :x:         | :heavy_check_mark: | :heavy_check_mark:  | :heavy_check_mark: |
|     [SDK version]: aws-sdk-java      |      1.10.76       |      1.12.312      |          -          |      1.12.312      |
|    [SDK version]: aws-sdk-java-v2    |         -          |         -          |      2.17.290       |         -          |


Important Notice and Contact Information
----------------------------------------

a) COSBench is not a product, and it does not have a full-time support team. Before you use this tool, please understand 
the need to invest enough effort to learn how to use it effectively and to address possible bugs.

b) To help COSBench develop further, please become an active member of the community and consider giving back by making
contributions.

c) **Wiki**: https://github.com/sine-io/cosbench-sineio/wiki

d) Email: sinecelia.wang@gmail.com, WeChat/Twitter: SineCelia


Licensing
---------

a) Intel source code is being released under the Apache 2.0 license.

b) Additional libraries used with COSBench have their own licensing; refer to 3rd-party-licenses.pdf for details.


Distribution Packages
---------------------

Please refer to "DISTRIBUTIONS.md" to get the link for distribution packages.


Installation & Usage
--------------------

Please refer to "COSBenchUserGuide.pdf" for details.

> **Version more than or equal to  0.4.7.9: should install telnet**
>
> **Version less than 0.4.7.9: should install nmap-ncat**


Adaptor Development
-------------------
If needed, adaptors can be developed for new storage services; please refer to "COSBenchAdaptorDevGuide.pdf" for details.


Build
-----
If a build from source code is needed, please refer to BUILD.md for details.


Resources
---------

Wiki: (https://github.com/intel-cloud/cosbench/wiki)

Issue tracking: (https://github.com/intel-cloud/cosbench/issues)

Mailing list: (http://cosbench.1094679.n5.nabble.com/)


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
