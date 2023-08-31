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

import java.io.*;

import com.intel.cosbench.bench.Snapshot;
import com.intel.cosbench.model.StageInfo;

/**
 * This class is the base class for exporting stage extra information.
 *
 * @author sine
 *
 */
abstract class AbstractStageExtraExporter implements StageExtraExporter {

    protected StageInfo stage;

    protected int numOpTypes;
    protected Snapshot[] snapshots;

    public AbstractStageExtraExporter() {
        /* empty */
    }

    public void setStage(StageInfo stage) {
        this.stage = stage;
    }

    @Override
    public void export(Writer writer) throws IOException {
        if ((snapshots = stage.getSnapshots()) == null)
            return;
        if (snapshots.length == 0)
            return;
        if ((numOpTypes = snapshots[0].getReport().getSize()) == 0)
            return;
        writeReport(writer);
    }

    private void writeReport(Writer writer) throws IOException {
        writeHeader(writer);
        writer.flush();
        for (Snapshot snapshot : snapshots)
            writeMetrics(writer, snapshot);
        writer.flush();
    }

    protected abstract void writeHeader(Writer writer) throws IOException;

    protected abstract void writeMetrics(Writer writer, Snapshot snapshot)
            throws IOException;

}
