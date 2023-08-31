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
package com.intel.cosbench.exporter;


import java.io.IOException;
import java.io.Writer;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import com.intel.cosbench.model.DriverInfo;

public class CSVDriverExtraExporter extends AbstractDriverExtraExporter{

    public CSVDriverExtraExporter() {
        /* empty */
    }
    
    protected void writeHeader(Writer writer) throws IOException {
    	// 2023.8.22, sine. overwrite, use common-csv.jar
    	final String[] header= {"Driver", "URL"};
    	
    	try {
            CSVFormat.DEFAULT.builder().setHeader(header).build().print(writer);
        } catch (Exception e) {
			e.printStackTrace();
		} finally {
            if(null != writer) {
            	writer.flush();
            }
        }
    }

    protected void writeMetrics(Writer writer, DriverInfo[] dInfos)throws IOException {
    	// 2023.8.22, sine. use common-csv.jar
    	try {
    		CSVPrinter csvPrinter = CSVFormat.DEFAULT.builder()
    				.setSkipHeaderRecord(true).build().print(writer);
    		
    		for (DriverInfo dInfo : dInfos) {
    			/* Driver */
    			String driver = dInfo.getName();
            	/* URL */
    			String  url = dInfo.getUrl();

                // write records.
                csvPrinter.printRecord(driver, url);
			}
        } catch (Exception e) {
			e.printStackTrace();
		} finally {
            if(null != writer) {
            	writer.flush();
            }
        }
    }
}
