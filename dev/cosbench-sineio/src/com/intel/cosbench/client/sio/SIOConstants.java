/**

MIT License

Copyright (c) 2021-Present SineIO

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

*/
package com.intel.cosbench.client.sio;


public interface SIOConstants {
    // --------------------------------------------------------------------------
    // CONNECTION
    // --------------------------------------------------------------------------
    String CONN_TIMEOUT_KEY = "timeout";
    int CONN_TIMEOUT_DEFAULT = 30000;
    
    // --------------------------------------------------------------------------
    // ENDPOINT
    // --------------------------------------------------------------------------
    String ENDPOINT_KEY = "endpoint";
    String ENDPOINT_DEFAULT = "http://s3.amazonaws.com";

    // --------------------------------------------------------------------------
    // AUTHENTICATION
    // --------------------------------------------------------------------------
    String AUTH_USERNAME_KEY = "accesskey";
    String AUTH_USERNAME_DEFAULT = "";

    String AUTH_PASSWORD_KEY = "secretkey";
    String AUTH_PASSWORD_DEFAULT = "";

    // --------------------------------------------------------------------------
    // CLIENT CONFIGURATION
    // --------------------------------------------------------------------------
    String PROXY_HOST_KEY = "proxyhost";
    String PROXY_PORT_KEY = "proxyport";

    // MAX CONNECTIONS DEFAULT
    // --------------------------------------------------------------------------
    String MAX_CONNECTIONS = "max_connections";
    int MAX_CONNECTIONS_DEFAULT = 50;

    // --------------------------------------------------------------------------
    // PATH STYLE ACCESS
    // --------------------------------------------------------------------------
    String PATH_STYLE_ACCESS_KEY = "path_style_access";
    boolean PATH_STYLE_ACCESS_DEFAULT = false;

    // --------------------------------------------------------------------------
    // CONTEXT NEEDS FROM AUTH MODULE
    // --------------------------------------------------------------------------
    String S3CLIENT_KEY = "s3client";
    
    // --------------------------------------------------------------------------
    // 2020.11.26, if true, will verify ssl.
    // NO VERIFY SSL
    // --------------------------------------------------------------------------
    String NO_VERIFY_SSL_KEY = "no_verify_ssl";
    boolean NO_VERIFY_SSL_DEFAULT = false;
    
    // --------------------------------------------------------------------------
    // 2021.2.7
    // StorageClass
    // --------------------------------------------------------------------------
    String STORAGE_CLASS_KEY = "storage_class";
    String STORAGE_CLASS_DEFAULT = "STANDARD";
    
    // --------------------------------------------------------------------------
    // 2021.7.11
    // RestoreDays
    // --------------------------------------------------------------------------
    String RESTORE_DAYS_KEY = "restore_days";
    int RESTORE_DAYS_DEFAULT = 1;
    
    // --------------------------------------------------------------------------
    // 2021.8.3, default: 5MiB
    // PartSize for Multipart upload.
    // --------------------------------------------------------------------------
    String PART_SIZE_KEY = "part_size";
    long PART_SIZE_DEFAULT = 5 * 1024 * 1024; // 5MiB
    
    // --------------------------------------------------------------------------
    // 2022.02.03, add region
    // Default region is US-EAST-1.
    // --------------------------------------------------------------------------
    String REGION_KEY = "aws_region";
    String REGION_DEFAULT = "us-east-1";

}
