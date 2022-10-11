/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * I2PGenericUnsafeBrowser.java
 * 2022 The I2P Project
 * http://www.i2p.net
 * This code is public domain.
 */

package net.i2p.apps.systray;

import java.io.File;
import java.util.Scanner;
import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import net.i2p.util.Log;

/**
 * I2PGenericUnsafeBrowser.java
 * Copyright (C) 2022 idk <hankhill19580@gmail.com>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the MIT License. In this case, part of that purpose
 * is to re-license it into the public domain. This is done with the consent
 * of the author of the code, who is also the author of this code.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * I2PGenericUnsafeBrowser is a wrapper which sets common environment
 * variables for the process controlled by a processbuilder. This process
 * contains a browser which is intended to be used for both browsing I2P
 * and for configuring I2P via the console. On Windows, this will always
 * default to MS Edge, the Chromium-based version.
 *
 * @author idk
 * @since 2.0.0
 */

public class I2PGenericUnsafeBrowser implements ClientApp {
  public String BROWSER = "";
  private Process p = null;
  protected final Log _log;
  protected final I2PAppContext _context;
  protected final ClientAppManager _mgr;
  protected boolean privateBrowsing = false;
  protected String[] _args = {""};

  // Ideally, EVERY browser in this list should honor http_proxy, https_proxy,
  // ftp_proxy and no_proxy. in practice, this is going to be hard to guarantee.
  // For now, we're just assuming.
  private static final String[] browsers = {
      // This debian script tries everything in $BROWSER, then gnome-www-browser
      // and x-www-browser
      // if X is running and www-browser otherwise. Those point to the user's
      // preferred
      // browser using the update-alternatives system.
      "sensible-browser",
      // another one that opens a preferred browser
      "xdg-open",
      // Try x-www-browser directly
      "x-www-browser", "gnome-www-browser",
      // general graphical browsers that aren't Firefox or Chromium based
      "defaultbrowser", // puppy linux
      "dillo", "seamonkey", "konqueror", "galeon", "surf",
      // Text Mode Browsers only below here
      "www-browser", "links", "lynx"};

  public I2PGenericUnsafeBrowser(I2PAppContext context, ClientAppManager mgr,
                                 String[] args) {
    //_state = UNINITIALIZED;
    _args = args;
    _mgr = mgr;
    _context = context;
    _log = _context.logManager().getLog(UrlLauncher.class);
  }
  public I2PGenericUnsafeBrowser() {
    _mgr = null;
    _context = I2PAppContext.getGlobalContext();
    _log = _context.logManager().getLog(UrlLauncher.class);
  }

  private static String getOperatingSystem() {
    String os = System.getProperty("os.name");
    if (os.startsWith("Windows")) {
      return "Windows";
    } else if (os.contains("Linux")) {
      return "Linux";
    } else if (os.contains("BSD")) {
      return "BSD";
    } else if (os.contains("Mac")) {
      return "Mac";
    }
    return "Unknown";
  }

  /**
   * Obtains the default browser for the Windows platform, which by now should
   * be Edgium in the worst-case scenario but in case it isn't, we can use this
   * function to figure it out. It can find:
   *
   * 1. The current user's HTTPS default browser if they configured it to be
   * non-default
   * 2. The current user's HTTP default browser if they configured it to be
   * non-default
   * 3. Edgium if it's available
   * 4. iexplore if it's not
   *
   * and it will return the first one we find in exactly that order.
   *
   * Adapted from:
   * https://stackoverflow.com/questions/15852885/method-returning-default-browser-as-a-string
   * and from:
   * https://github.com/i2p/i2p.i2p/blob/master/apps/systray/java/src/net/i2p/apps/systray/UrlLauncher.java
   *
   * @return path to the default browser ready for execution. Empty string on
   *     Linux and OSX.
   */
  public String getDefaultWindowsBrowser() {
    if (getOperatingSystem() == "Windows") {
      String defaultBrowser = getDefaultOutOfRegistry(
          "HKEY_CURRENT_USER\\SOFTWARE\\Microsoft\\Windows\\Shell\\Associations\\URLAssociations\\https\\UserChoice");
      if (defaultBrowser != "")
        return defaultBrowser;
      defaultBrowser = getDefaultOutOfRegistry(
          "HKEY_CURRENT_USER\\SOFTWARE\\Microsoft\\Windows\\Shell\\Associations\\URLAssociations\\http\\UserChoice");
      if (defaultBrowser != "")
        return defaultBrowser;
      defaultBrowser = getDefaultOutOfRegistry(
          "HKEY_LOCAL_MACHINE\\SOFTWARE\\Classes\\microsoft-edge\\shell\\open\\command");
      if (defaultBrowser != "")
        return defaultBrowser;
      defaultBrowser = getDefaultOutOfRegistry(
          "HKEY_CLASSES_ROOT\\http\\shell\\open\\command");
      if (defaultBrowser != "")
        return defaultBrowser;
    }
    return "";
  }

  /**
   * obtains information out of the Windows registry.
   *
   * @param hkeyquery registry entry to ask for.
   * @return
   */
  public String getDefaultOutOfRegistry(String hkeyquery) {
    if (getOperatingSystem() == "Windows") {
      try {
        // Get registry where we find the default browser
        Process process = Runtime.getRuntime().exec("REG QUERY " + hkeyquery);
        Scanner kb = new Scanner(process.getInputStream());
        while (kb.hasNextLine()) {
          String line = kb.nextLine();
          if (line.contains("(Default")) {
            String[] splitLine = line.split("  ");
            kb.close();
            return splitLine[splitLine.length - 1]
                .replace("%1", "")
                .replaceAll("\\s+$", "")
                .replaceAll("\"", "");
          }
        }
        // Match wasn't found, still need to close Scanner
        kb.close();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    return "";
  }

  private static String scanAPath(String dir) {
    for (String browser : browsers) {
      File test = new File(dir, browser);
      if (test.exists()) {
        return test.getAbsolutePath();
      }
    }
    return "";
  }

  /**
   * Find any browser in our list within a UNIX path
   *
   * @return
   */
  public String getAnyUnixBrowser() {
    // read the PATH environment variable and split it by ":"
    String[] path = System.getenv("PATH").split(":");
    if (path != null && path.length > 0) {
      for (String p : path) {
        String f = scanAPath(p);
        if (f != "") {
          return f;
        }
      }
    }
    return "";
  }

  /**
   * Find any usable browser and output the whole path
   *
   * @return
   */
  public String findUnsafeBrowserAnywhere() {
    if (BROWSER != "") {
      File f = new File(BROWSER);
      if (f.exists())
        return f.getAbsolutePath();
    }

    if (getOperatingSystem() == "Windows") {
      return getDefaultWindowsBrowser();
    }
    return getAnyUnixBrowser();
  }

  //
  public ProcessBuilder baseProcessBuilder(String[] args) {
    String browser = findUnsafeBrowserAnywhere();
    if (!browser.isEmpty()) {
      int arglength = 0;
      if (args != null)
        arglength = args.length;
      String[] newArgs;
      if (getOperatingSystem() == "windows" && browser.toLowerCase().contains("edge")) {
        newArgs = new String[arglength + 2];
        newArgs[0] = browser;
        newArgs[1] = "--user-data-dir=" + runtimeDirectory(true);
        if (args != null && arglength > 0) {
          for (int i = 0; i < arglength; i++) {
            newArgs[i + 2] = args[i];
          }
        }
      } else {
        newArgs = new String[arglength + 1];
        newArgs[0] = browser;
        if (args != null && arglength > 0) {
          for (int i = 0; i < arglength; i++) {
            newArgs[i + 1] = args[i];
          }
        }
      }
      ProcessBuilder pb =
          new ProcessBuilder(newArgs).directory(runtimeDirectory(true));
      pb.environment().put("http_proxy", "http://127.0.0.1:4444");
      pb.environment().put("https_proxy", "http://127.0.0.1:4444");
      pb.environment().put("ftp_proxy", "http://127.0.0.1:0");
      pb.environment().put("all_proxy", "http://127.0.0.1:4444");
      pb.environment().put("no_proxy", "http://127.0.0.1:7657");
      pb.environment().put("HTTP_PROXY", "http://127.0.0.1:4444");
      pb.environment().put("HTTPS_PROXY", "http://127.0.0.1:4444");
      pb.environment().put("FTP_PROXY", "http://127.0.0.1:0");
      pb.environment().put("ALL_PROXY", "http://127.0.0.1:4444");
      pb.environment().put("NO_PROXY", "http://127.0.0.1:7657");
      return pb;
    } else {
      _log.info("No Browser found.");
      return null;
    }
  }

  /**
   * delete the runtime directory
   *
   * @return true if successful, false if not
   */
  public boolean deleteRuntimeDirectory() {
    File rtd = runtimeDirectory(true);
    if (rtd.exists()) {
      rtd.delete();
      return true;
    }
    return false;
  }

  /**
   * get the runtime directory, creating it if create=true
   *
   * @param create if true, create the runtime directory if it does not exist
   * @return the runtime directory, or null if it could not be created
   * @since 2.0.0
   */
  public File runtimeDirectory(boolean create) {
    String rtd = runtimeDirectory();
    return runtimeDirectory(create, rtd);
  }

  public File runtimeDirectory(boolean create, String rtd) {
    if (rtd.isEmpty() || rtd == null) {
      File rtdFile = new File(_context.getConfigDir().toString(), "browser");
      if (rtdFile.exists()) {
        if (rtdFile.isDirectory()) {
          return rtdFile;
        }
      }
      rtdFile.mkdirs();
      return rtdFile;
    }
    return new File(rtd);
  }

  public String runtimeDirectory(String rtd) {
    return runtimeDirectory(true, rtd).toString();
  }

  /**
   * get the correct runtime directory
   *
   * @return the runtime directory, or null if it could not be created or found
   * @since 2.0.0
   */
  public String runtimeDirectory() {
    // get the I2P_BROWSER_DIR environment variable
    String rtd = System.getenv("I2P_BROWSER_DIR");
    // if it is not null and not empty
    if (rtd != null && !rtd.isEmpty()) {
      // check if the file exists
      File rtdFile = new File(rtd);
      if (rtdFile.exists()) {
        // if it does, return it
        return rtd;
      }
    }
    return runtimeDirectory("");
  }

  public Process launchAndDetatch(boolean privateWindow, String[] url) {
    // validateUserDir();
    //  if (waitForProxy()) {
    ProcessBuilder pb;
    if (privateWindow) {
      pb = baseProcessBuilder(url);
    } else {
      pb = baseProcessBuilder(url);
    }
    try {
      _log.info(pb.command().toString());
      p = pb.start();
      _log.info("I2PBrowser");
      sleep(2000);
      return p;
    } catch (Throwable e) {
      _log.info(e.toString());
    }
    //}
    return null;
  }

  public void launch(boolean privateWindow, String[] url) {
    p = launchAndDetatch(privateWindow, url);
    try {
      _log.info("Waiting for I2PBrowser to close...");
      int exit = p.waitFor();
      if (privateWindow) {
        if (deleteRuntimeDirectory())
          _log.info("Private browsing enforced, deleting runtime directory");
      }
      _log.info("I2PBrowser exited with value: " + exit);
    } catch (Exception e) {
      _log.info("Error: " + e.getMessage());
    }
  }

  private static void sleep(int millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException bad) {
      bad.printStackTrace();
      throw new RuntimeException(bad);
    }
  }

  @Override
  public void startup() {
    // TODO
    this.launch(privateBrowsing, _args);
  }

  @Override
  public void shutdown(String[] args) {
    // try {
    p.destroy();
    //}catch()
  }

  @Override
  public ClientAppState getState() {
    if (p.isAlive()) {
      return ClientAppState.RUNNING;
    }
    int exitCode = p.exitValue();
    if (exitCode != 0) {
      return ClientAppState.CRASHED;
    }
    return ClientAppState.STOPPED;
  }

  @Override
  public String getName() {
    return "DefaultBrowser";
  }

  @Override
  public String getDisplayName() {
    return "DefaultBrowser";
  }
}
