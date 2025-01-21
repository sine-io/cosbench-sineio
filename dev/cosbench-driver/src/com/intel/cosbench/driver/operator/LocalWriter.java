/**

Copyright 2021-Present SineIO, All Rights Reserved.

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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Date;
import java.util.Random;

import org.apache.commons.io.IOUtils;

import com.intel.cosbench.bench.Result;
import com.intel.cosbench.bench.Sample;
import com.intel.cosbench.config.Config;
import com.intel.cosbench.driver.generator.RandomInputStream;
import com.intel.cosbench.driver.generator.XferCountingInputStream;
import com.intel.cosbench.driver.util.ObjectPicker;
import com.intel.cosbench.driver.util.SizePicker;
import com.intel.cosbench.service.AbortedException;

/**
 * This class represents primitive LOCALWRITE operation.
 *
 * @author sine
 *
 */
class LocalWriter extends AbstractOperator {

	public static final String OP_TYPE = "localwrite";

	private boolean chunked;
	private boolean isRandom;
	private boolean hashCheck = false;
	private ObjectPicker objPicker = new ObjectPicker();
	private SizePicker sizePicker = new SizePicker();
	
	private File folder;
	
	public LocalWriter() {
		/* empty */
	}

	@Override
	protected void init(String id, int ratio, String division, Config config) {
		super.init(id, ratio, division, config);
		objPicker.init(division, config);
		sizePicker.init(config);
		chunked = config.getBoolean("chunked", false);
		isRandom = !config.get("content", "random").equals("zero");
		hashCheck = config.getBoolean("hashCheck", false);
		
		String filepath = config.get("files");
		folder = new File(filepath);
		
		if (!folder.exists()) {
			throw new RuntimeException("Folder " + folder + " does not exist.");
		}
		
		if (!folder.isDirectory()) {
			throw new RuntimeException(folder + "is not a directory, please check.");
		}
	}

	@Override
	public String getOpType() {
		return OP_TYPE;
	}

	@Override
	protected void operate(int idx, int all, Session session) {
		Sample sample;
		
		if (!folder.canWrite()) {
			doLogErr(session.getLogger(), 
					"fail to perform localwrite operation, can not write " + folder.getAbsolutePath());
			sample = new Sample(new Date(), getId(), getOpType(), getSampleType(), getName(), false);
		}
		
		Random random = session.getRandom();
		long size = sizePicker.pickObjSize(random);
		long len = chunked ? -1 : size;
		String[] path = objPicker.pickObjPath(random, idx, all);
		RandomInputStream in = new RandomInputStream(size, random, isRandom, hashCheck);
		sample = doLocalWrite(folder, in, len, path[0], path[1], session, this);
		session.getListener().onSampleCreated(sample);
		Date now = sample.getTimestamp();
		Result result = new Result(now, getId(), getOpType(), getSampleType(), getName(), sample.isSucc());
		session.getListener().onOperationCompleted(result);
	}

	public static Sample doLocalWrite(File folder, InputStream in, long length, String conName, String objName, Session session, Operator op) {
		if (Thread.interrupted())
			throw new AbortedException();

		XferCountingInputStream cin = new XferCountingInputStream(in);
		long start = System.nanoTime();

		String fileName = folder.getAbsolutePath() + "/" + conName + "/" + objName;
		File file = new File(fileName);
		file.getParentFile().mkdirs(); // create dir '#conName'
		
		FileOutputStream fos = null;

		try {
			fos = new FileOutputStream(fileName);
			IOUtils.copyLarge(cin, fos);
			
			if (file.length() != length ) {
				doLogWarn(session.getLogger(), "File length does not matched(expected:" +length + ", actual:" + file.length() + ")");
				return new Sample(new Date(), op.getId(), op.getOpType(), op.getSampleType(), op.getName(), false);
			}
			
			doLogInfo(session.getLogger(), "File" + fileName + "copied, " + "length is " + length);
		} catch (Exception e) {
			String msg = "LocalWrite failed: " + fileName;
			doLogWarn(session.getLogger(), msg, e);

			return new Sample(new Date(), op.getId(), op.getOpType(), op.getSampleType(), op.getName(), false);
		} finally {
			IOUtils.closeQuietly(cin);
			IOUtils.closeQuietly(fos);
		}

		long end = System.nanoTime();
		return new Sample(new Date(), op.getId(), op.getOpType(), op.getSampleType(), op.getName(), true,
				(end - start) / 1000000, cin.getXferTime(), cin.getByteCount());
	}
}
