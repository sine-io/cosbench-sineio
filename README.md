COSBench - Cloud Object Storage Benchmark
=========================================

New feature: aws_region for s3v2.
-----------
e.g. :

	```xml
	<storage type="s3v2" config="endpoint=xxx;aws_region=what-you-want" />
	<operation type="write" ratio="100" config="cprefix=fullstack0..." />
	```
	
	```xml
	<storage type="s3v2" config="endpoint=xxx;aws_region=us-east-1" />
	
	<workstage name="init">
      <work type="init" workers="1" config="cprefix=concurrenttest-0;containers=r(1,1)" />
    </workstage>
	```
	
	Note: 
	Create bucket: Accordingly, the signature calculations in Signature Version 4 must use us-east-1 as the Region, even if the location constraint in the request specifies another Region where the bucket is to be created.
	(https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateBucket.html)


New feature from bissenbay/s3-range-and-prefetch, thanks for this PR.
-----------
e.g. :

	```xml
        <workstage name="prefetch">
          <work name="prefetch" workers="10" totalOps="10">
            <storage type="s3" config="accesskey=<accesskey>;secretkey=<secretkey>;is_prefetch=true;endpoint=<endpoint>;path_style_access=true" />
            <operation type="read" ratio="100" config="containers=r(1,1);objects=r(1,10)" />
          </work>
        </workstage>
	<workstage name="range">
          <work name="range" workers="10" totalOps="10">
            <storage type="s3" config="...;is_range_request=true;file_length=15000000;chunk_length=5000000;path_style_access=true" />
            <operation type="read" ratio="100" config="containers=r(1,1);objects=r(1,10)" />
          </work>
        </workstage>
	```

Add new feature: head object
-----------
e.g. :

	```xml
	<storage type="s3" config="endpoint=xxx" />
	<operation type="head" ratio="100" config="cprefix=fullstack0..." />
	```

New Storage: s3v2
-----------
same to s3 except sdk version: s3v2 is aws-sdk-java-v2.  
e.g. : 

	```xml
	<storage type="s3v2" config="endpoint=xxx" />
	```

Add new feature: GiB, MiB, KiB
-----------
Now GB is 10^n, GiB is 2^n.  
e.g. :

	```xml
	<operation type="write" ratio="100" config="cprefix=fullstack0;containers=c(1);sizes=c(1)GiB" />
	<operation type="write" ratio="100" config="cprefix=fullstack0;containers=c(1);sizes=c(1)MiB" />
	<operation type="write" ratio="100" config="cprefix=fullstack0;containers=c(1);sizes=c(1)KiB" />
	```

Multipart upload Usage
-----------
Add Multipart upload method and part_size parameter: You can set it now. Default is 5MiB.  
e.g. : 

	```xml
	<operation type="mwrite" ...>
	<storage type="s3" config="part_size=5242880;...path_style_access=true;timeout=100000"/>
	```

Restore Usage
-----------
Add Restore method and restore_days parameter: restore_days. You can set it now. Default is 1.  
e.g. : 

	```xml
	<operation type="restore" ...>
	<storage type="s3" config="restore_days=1;...path_style_access=true;timeout=100000"/>
	```

StorageClass Usage
-----------
e.g. :

	```xml
	<storage type="s3" config="endpoint=xxx;storage_class=GLACIER" />
	<operation type="write" ratio="100" config="cprefix=fullstack0..." />
	```

HTTPS Usage
-----------
If https, please set no_verify_ssl to true.  
e.g. :

	```xml
	<storage type="s3" config="accesskey=accesskey;secretkey=secretkey;endpoint=https://ip:port;no_verify_ssl=true;path_style_access=true"/>
	```

= END =
========================================= 
