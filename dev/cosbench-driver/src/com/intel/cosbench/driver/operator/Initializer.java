/**

Copyright 2013 Intel Corporation, All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.intel.cosbench.driver.operator;

public class Initializer extends Preparer {

    public static final String OP_TYPE = "init";

    @Override
    public String getOpType() {
        return OP_TYPE;
    }
    
    // 2023.8.17, sine. Preparer's SampleType is write, change this.
	@Override
	public String getSampleType() {
		return OP_TYPE;
	}

}
