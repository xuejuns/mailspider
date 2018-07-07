package com.gumirov.shamil.partsib.processors;

import com.gumirov.shamil.partsib.MainRouteBuilder;
import com.gumirov.shamil.partsib.plugins.FileMetaData;
import com.gumirov.shamil.partsib.plugins.Plugin;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.gumirov.shamil.partsib.MainRouteBuilder.ENDPOINT_ID_HEADER;
import static com.gumirov.shamil.partsib.MainRouteBuilder.MID;

/**
 * NOTE: We write to log and mark as SUCCESS in case of any error (exception) happened and rolling back to original 
 * content. 
 */
public class PluginsProcessor implements Processor {
  static Logger log = LoggerFactory.getLogger(PluginsProcessor.class);
  private List<Plugin> plugins;

  public PluginsProcessor(List<Plugin> plugins) {
    this.plugins = plugins;
  }

  @Override
  public void process(Exchange exchange) {
    exchange.getIn().setHeader(MainRouteBuilder.PLUGINS_STATUS_OK, Boolean.TRUE);
//    log.info("PLUGINS: id="+exchange.getExchangeId()+" file="+exchange.getIn().getHeader(Exchange.FILE_NAME, String.class));
    if (plugins == null || plugins.size() == 0) {
      log.info("No plugins loaded");
      return;
    }
    Plugin last = null;
    try {
      FileMetaData metadata = new FileMetaData(
          exchange.getIn().getHeader(ENDPOINT_ID_HEADER).toString(),
          exchange.getIn().getHeader(Exchange.FILE_NAME).toString(),
          exchange.getIn().getBody(InputStream.class),
          exchange.getIn().getHeaders());
      ArrayList<File> filesToDelete = new ArrayList<>(); //can contain Files and Strings
      for (Plugin plugin : plugins) {
        last = plugin;
        if (metadata.is.available() == 0) {
          java.io.BufferedInputStream bis = new BufferedInputStream(metadata.is);
          if (!bis.markSupported() || bis.read() == -1)
            throw new IllegalArgumentException("File to process is empty: " + metadata.filename);
        }
        InputStream is = plugin.processFile(metadata, LoggerFactory.getLogger(plugin.getClass().getSimpleName()));
        if (is != null) {
          log.debug("["+exchange.getIn().getHeader(MID)+"]"+" Plugin "+plugin.getClass().getSimpleName()+" CHANGED file: "+metadata.filename);
          metadata.is = is;
          //broken Collections.copy((List<File>)metadata.headers.get(FileMetaData.TEMP_FILE_HEADER), filesToDelete);
          for (File f : (List<File>)metadata.headers.get(FileMetaData.TEMP_FILE_HEADER)) {
            filesToDelete.add(f);
          }
          exchange.getIn().setHeader(MainRouteBuilder.LENGTH_HEADER, is.available());
        } else {
          log.debug("["+exchange.getIn().getHeader(MID)+"]"+" Plugin "+plugin.getClass().getSimpleName()+" DID NOT CHANGE file: "+metadata.filename);
        }
        if (metadata.headers != null) {
          exchange.getIn().setHeaders(metadata.headers);
        } else {
          log.warn("["+exchange.getIn().getHeader(MID)+"]"+" Plugin MUST NOT return null headers: "+plugin.getClass().getSimpleName());
        }
      }
      exchange.getIn().setBody(metadata.is);
      exchange.getIn().setHeader(MainRouteBuilder.PLUGINS_STATUS_OK, Boolean.TRUE);
      for (File f : filesToDelete) {
        if (!f.delete()) {
          log.warn("PluginProcessor: problem while deleting temp plugin files: cannot delete file=%s", f.getAbsolutePath());
          f.deleteOnExit();
        } else log.info("PluginProcessor: temp file deleted name=" + f.getAbsolutePath());
      }
    } catch (Exception e) {
      log.error("["+exchange.getIn().getHeader(MID)+"]"+" Error for file="+exchange.getIn().getHeader(Exchange.FILE_NAME, String.class)+" in plugin="+last.getClass().getSimpleName()+". ABORTING transaction marking it as SUCCESS (we will NOT process same incoming again). Please manual process this. Exception = "+e.getMessage(), e);
      exchange.getIn().setHeader(MainRouteBuilder.PLUGINS_STATUS_OK, Boolean.FALSE);
    }
  }
}
