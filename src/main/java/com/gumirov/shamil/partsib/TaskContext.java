package com.gumirov.shamil.partsib;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

/**
 * Context used in plugins API.
 * (c) 2017 by Shamil Gumirov (shamil@gumirov.com).<br/>
 * Date: 4/1/2017 Time: 00:38<br/>
 */
public class TaskContext {
  private String taskId;
  private Date startTime;
  /**
   * Used to store which plugin processed this workload. Actually this is a free-form log.
   */
  private ArrayList<String> processingLog;

  private ArrayList<TaskFile> files;
}

class TaskFile {
  String name;
  File path;
}
