package com.intel.cosbench.log;

import java.io.File;
import java.io.IOException;

public interface LogManager {
    void dispose();
    Logger getLogger();
    LogLevel getLogLevel();
    void setLogLevel(LogLevel level);
    void setLogFile(File dir, String filename, boolean append, boolean buffer) throws IOException;
    void enableConsole();
    String getLogAsString() throws IOException;
}
