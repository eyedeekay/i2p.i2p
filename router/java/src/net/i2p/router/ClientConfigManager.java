package net.i2p.router;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import net.i2p.router.startup.WorkingDir;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.FileUtil;
import net.i2p.util.SystemVersion;

/**
 * Execute bulk edits against I2P application config files or config
 * directories. This is a command-line application only, which is intended
 * to work agnostic of whether the router is runnning.
 *
 * @author idk 2023
 */
public class ClientConfigManager extends WorkingDir {
  private String clientName = null;
  private String workDir = null;
  /**
   * editPropertiesFile opens a single properties(.config) file and edits
   * every entry in it that matches the regular expression `prop` to contain
   * the value `value`.
   * @param clientAppConfig
   * @param prop
   * @param value
   * @return
   */
  private boolean editPropertiesFile(File clientAppConfig, String prop,
                                     String value)
      throws FileNotFoundException {
    /// if (_log.shouldLog(Log.INFO)){
    //_log.info("editing config file " + clientAppConfig.getName());
    System.out.println("editing config file " + clientAppConfig.getName());
    //}
    boolean go = true;
    Properties clientAppConfigProps = new Properties();
    File backupConfig = new File(clientAppConfig + ".bak");
    FileUtil.copy(clientAppConfig, backupConfig, true,
                false);
    try {
      FileInputStream clientAppConfigReader = new FileInputStream(backupConfig);
      clientAppConfigProps.load(clientAppConfigReader);
      final Iterator entries = clientAppConfigProps.entrySet().iterator();
      while (entries.hasNext()) {
        final Map.Entry entry = (Map.Entry)entries.next();
        String key = (String)entry.getKey();
        if (Pattern.matches(prop, key)) {
          clientAppConfigProps.setProperty(key, value);
          // System.out.println("set property " + key + "=" + value);
        }
      }
      try {
        FileWriter clientAppConfigWriter = new FileWriter(clientAppConfig);
        clientAppConfigProps.store(clientAppConfigWriter,
                                   "inserted by ClientConfigManager");
      } catch (IOException e) {
        go = false;
      }
    } catch (IOException e) {
      go = false;
    } catch (IllegalStateException e) {
      go = false;
    }
    return go;
  }
  /**
   * editClientsConfig checks clients.config and clients.config.d to enable
   * bulk configuration of client applications
   *
   * @param configDir
   * @param prop
   * @param value
   * @return
   * @throws FileNotFoundException
   */
  public void editClientsConfig(File configDir, String prop, String value)
      throws FileNotFoundException {
    File clientAppConfigDir = new File(configDir, "clients.config.d");
    if (clientAppConfigDir.exists()) {
      File[] clientAppConfigFiles =
          clientAppConfigDir.listFiles(new FileSuffixFilter(".config"));
      if (clientAppConfigFiles != null) {
        for (int i = 0; i < clientAppConfigFiles.length; i++) {
          boolean cont =
              editPropertiesFile(clientAppConfigFiles[i], prop, value);
          if (!cont)
            break;
        }
      }
    } else {
      File clientAppConfig = new File(configDir, "clients.config");
      if (!clientAppConfig.exists())
        throw new FileNotFoundException();
      editPropertiesFile(clientAppConfig, prop, value);
    }
  }
  /**
   * editClientAppConfig edits the configuration files for a client based
   * on the client's name(the un-translated one). Common values would be
   * `i2ptunnel` or `i2psnark`. It can handle both monolithic and split-file
   * confiurations I2P apps.
   *
   * @param configDir
   * @param clientName
   * @param prop
   * @param value
   * @return
   * @throws FileNotFoundException
   */
  public void editClientAppConfig(File configDir, String clientName,
                                  String prop, String value)
      throws FileNotFoundException {
    File i2pTunnelConfigDir = new File(configDir, clientName + ".config.d");
    if (i2pTunnelConfigDir.exists()) {
      File[] i2pTunnelConfigFiles =
          i2pTunnelConfigDir.listFiles(new FileSuffixFilter(".config"));
      if (i2pTunnelConfigFiles != null) {
        for (int i = 0; i < i2pTunnelConfigFiles.length; i++) {
          boolean cont =
              editPropertiesFile(i2pTunnelConfigFiles[i], prop, value);
          if (!cont)
            break;
        }
      }
    } else {
      File i2pTunnelConfig = new File(configDir, clientName + ".config");
      if (!i2pTunnelConfig.exists())
        throw new FileNotFoundException();
      editPropertiesFile(i2pTunnelConfig, prop, value);
    }
  }
  private class PropSet {
    String key;
    String value;
    PropSet(String inkey, String invalue) {
      key = inkey;
      value = invalue;
    }
    public String toString() {
      if (key == null) {
        return null;
      }
      if (value == null) {
        return key + "=";
      }
      return key + "=" + value;
    }
  }
  /**
   * parses arguments and flags from the command line. Valid flags are:
   *
   *  -clientapp name
   *  -workdir path
   *
   * which must be followed by 2 trailing arguments, the first of which is
   * a regular expression, the second of which is a value which will be applied
   * to all keys matching the regular expression.
   *
   * @param args
   * @return PropSet containing the regex to match against the key, and the
   *     value to set
   * @throws Error
   */
  public PropSet parseArgs(String args[]) throws Error {
    if (args.length < 2) {
      throw new Error("insufficient bulk edit arguments");
    } else if (args.length == 2) {
      return new PropSet(args[0], args[1]);
    }
    String key = null;
    String value = null;
    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith("-")) {
        if (args[i].equals("-clientapp")) {
          if (args.length >= i + 1) {
            System.out.println("Setting clientName to " + args[i] +
                               args[i + 1]);
            clientName = args[i + 1];
            i++;
          } else
            throw new Error(
                "Insufficient arguments, -clientapp requires an an application name");
        } else if (args[i].equals("-clientapp")) {
          if (args.length >= i + 1) {
            System.out.println("Setting workDir to " + args[i] + args[i + 1]);
            workDir = args[i + 1];
            i++;
          } else
            throw new Error(
                "Insufficient arguments, -workdir requires an a directory");
        }
      } else {
        if (key == null)
          key = args[i];
        else {
          value = args[i];
          break;
        }
      }
    }
    return new PropSet(key, value);
  }

  /**
   * editClientApp helps determine if we're editing clients.config or an
   * application config.
   *
   * @return true if editing an app, false if editing clients.config
   */
  public boolean editClientApp() {
    if (clientName != null)
      return true;
    return false;
  }
  /**
   * get the "default" config directory for your platform.
   *
   * @return the default configuration directory, or workDir if it is set.
   */
  public File getConfigDir() {
    if (workDir != null) {
      System.out.println("workdir set to" + workDir);
      return new File(workDir);
    }
    System.out.println("finding default config dir");
    boolean isWindows = SystemVersion.isWindows();
    String defaultPath = getDefaultDir(isWindows).getAbsolutePath();
    System.out.println("using default config dir " + defaultPath);
    return new File(defaultPath);
  }
  public static void main(String args[]) {
    System.out.println("ClientConfigManager");
    ClientConfigManager ccm = new ClientConfigManager();
    try {
      PropSet propSet = ccm.parseArgs(args);
      System.out.println("args are " + propSet.toString());
      File configDir = ccm.getConfigDir();
      System.out.println("configs are " + configDir.getAbsolutePath());
      if (ccm.editClientApp()) {
        try {
          ccm.editClientAppConfig(configDir, ccm.clientName, propSet.key,
                                  propSet.value);
        } catch (FileNotFoundException e) {
          System.out.println(
              "Client not found, check name and config directory");
        }
      } else {
        try {
          ccm.editClientsConfig(configDir, propSet.key, propSet.value);
        } catch (FileNotFoundException e) {
          System.out.println(
              "Client config file not found, check config directory");
        }
      }
    } catch (Error e) {
      System.out.println(e.toString());
    }
  }
}
