/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * ScheduledService.java
 * 2021 The I2P Project
 * http://www.geti2p.net
 * This code is public domain.
 */

/**
 * Router-managed tool for running scheduled tasks as ClientApps
 * managed by the console. Can be used for periodic read-only git
 * fetches, torrent generation, RSS feed generation, static site
 * generation, basically anything that requires brief, repeated
 * execution.
 *
 * Scheduling a task in I2P might be a risk for services in some
 * cases. It could be regarded as a behavioral indicator, and running
 * at a specific time every single day could hypothetically produce
 * a predictable "burst" of traffic potentially providing a piece of
 * data to an attacker controlling routers in the tunnels(if it
 * requires touching the network to complete). To combat  this issue,
 * the scheduler permits the user to specify a range of valid times,
 * and will select a random time within that range to begin it's task.
 * In this way it differs from `cron` or other solutions.
 *
 * It uses a config file with an intentionally cron-like syntax. Unlike
 * with cron he config file must contain exactly one task. This config
 * file is passed in the `args` field of the `ScheduledService`
 * clients.config, and may have any name and run any task present on the
 * system or shipped with the hypothetical ScheduledService plugin.
 *
 * Available options(For the config file):
 *
 *  * `numeric` tasks identical to traditional cron tasks.
 *  * @hourly tasks that run at a *random* time every hour.
 *  * @daily tasks that run at a *random* time every day.
 *  * @weekly tasks that run at a *random* time every week.
 *  * @monthly tasks that run at a *random* time, once a month.
 *  * `numeric-range` tasks that run at a *random* time, within a
 *   specified range, using the nonstandard(for cron) syntax `start-end`
 *
 * it is deliberately *imprecise* in that it does not check if the task
 * is ready to run in real-time, instead, it checks once every minute. It
 * DOES NOT support fractional minutes, tasks that specify fractional minutes
 * will run at the next minute.
 *
 * @author eyedeekay
 * @since 1.8.0/0.9.54
 */

package net.i2p.router.web;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.Calendar;
import java.util.Date;
import java.util.Scanner;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

public class ScheduledService implements ClientApp  {
    private static final String PLUGIN_DIR = "plugins";

    private final Log _log;
    private final I2PAppContext _context;
    private final ClientAppManager _cmgr;
    private final CronTab crontab;

    private ClientAppState _state = ClientAppState.UNINITIALIZED;

    private volatile String name = "unnamedClient";
    private volatile String displayName = "unnamedClient";
    private boolean shutdown = false;

    public ScheduledService(I2PAppContext context, ClientAppManager listener, String[] args) throws FileNotFoundException {
        _context = context;
        _cmgr = listener;
        _log = context.logManager().getLog(ScheduledService.class);

        if (args.length == 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No config file specified for ScheduledService");
        }

        File configFile = new File(args[0]);

        if (!configFile.exists()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Config file for ScheduledService does not exist: " + configFile.getAbsolutePath());
            throw new FileNotFoundException("Config file for ScheduledService does not exist: " + configFile.getAbsolutePath());
        }

        crontab = new CronTab(configFile);

        if (crontab == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("No tasks found in config file for ScheduledService");
            return;
        }

        changeState(ClientAppState.INITIALIZED, "ShellService: " + getName() + " setup and initialized");
    }

    private File myPluginDir() {
        String tmp_name = this.getName();
        File pluginDir = new File(_context.getConfigDir(), PLUGIN_DIR + '/' + tmp_name);
        if (!pluginDir.exists()) {
            pluginDir = new File(_context.getConfigDir(), PLUGIN_DIR + '/' + tmp_name+"-"+SystemVersion.getOS()+"-"+SystemVersion.getArch());
            if (!pluginDir.exists()) {
                pluginDir = new File(_context.getConfigDir(), PLUGIN_DIR + '/' + tmp_name+"-"+SystemVersion.getOS());
                if (!pluginDir.exists()) {
                    throw new RuntimeException("Plugin directory does not exist: " + pluginDir.getAbsolutePath());
                } else {
                    this.name = tmp_name+"-"+SystemVersion.getOS();
                    if (_log.shouldDebug())
                        _log.debug("ScheduledService: Plugin name revised to match directory: " + this.getName());
                }
            } else {
                this.name = tmp_name+"-"+SystemVersion.getOS()+"-"+SystemVersion.getArch();
                if (_log.shouldDebug())
                    _log.debug("ScheduledService: Plugin name revised to match directory: " + this.getName());
            }
        }
        return pluginDir;
    }

    private synchronized void changeState(ClientAppState newState, String message, Exception ex) {
        if (_state != newState) {
            if (_log.shouldLog(Log.INFO))
                _log.info("ScheduledService state change: " + _state + " -> " + newState + ": " + message);
            _state = newState;
            _cmgr.notify(this, newState, message, ex);
        }
    }

    private synchronized void changeState(ClientAppState newState, String message) {
        changeState(newState, message, null);
    }

    public synchronized void startup() throws IOException, InterruptedException {
        if (_state != ClientAppState.UNINITIALIZED)
            return;
        changeState(ClientAppState.STARTING, "ScheduledService Starting up");
        _log.info("Starting ScheduledService");
        changeState(ClientAppState.RUNNING, "ScheduledService started");
        _log.info("ScheduledService started");
        RunLoop();
    }

    public synchronized void shutdown(String[] args) {
        if (_state != ClientAppState.RUNNING)
            return;
        changeState(ClientAppState.STOPPING, "ScheduledService Stopping");
        shutdown = true;
        changeState(ClientAppState.STOPPED, "ScheduledService Stopped");
        _log.info("ScheduledService stopped");
    }

    /**
     * Query the state of managed process and determine if it is running
     * or not. Convert to corresponding ClientAppState and return the correct
     * value.
     *
     * @return non-null
     */
    public ClientAppState getState() {
        return _state;
    }

    /**
     * The generic name of the ClientApp, used for registration,
     * e.g. "console". Do not translate. Has a special use in the context of
     * ScheduledService, must match the plugin name.
     *
     * @return non-null
     */
    public String getName() {
        if (name == "") {
            displayName = "ScheduledService-" + crontab.getCronCommand().split(" ")[0];
        }
        return name;
    }

    /**
     * The display name of the ClientApp, used in user interfaces.
     * The app must translate.
     *
     * @return non-null
     */
    public String getDisplayName() {
        if (displayName == "") {
            displayName = "ScheduledService: " + crontab.getCronCommand().split(" ")[0];
        }
        return displayName;
    }

    /*
     * Runs a loop until shutdown is set to true, which checks once a minute if a ScheduledService
     * needs to run.
     *
    */
    public void RunLoop() throws IOException, InterruptedException {
        while(!shutdown){
            if (crontab != null) {
                if (crontab.TimeToRun())
                    RunTask();
            }
            Thread.sleep(60000);
        }
    }

    private boolean RunTask() throws IOException, InterruptedException {
        if (_log.shouldLog(Log.INFO))
            _log.info("Running task: " + crontab.getCronCommand());
        File _errorLog = new File(myPluginDir(), "error.log");
        File _outputLog = new File(myPluginDir(), "output.log");
        String[] cmd = crontab.getCronCommand().split(" ");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(myPluginDir());
        pb.redirectOutput(_outputLog);
        pb.redirectError(_errorLog);
        Process p = pb.start();
        p.waitFor();

        int status = p.exitValue();

        if (status != 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Task failed: " + crontab.getCronCommand());
            changeState(ClientAppState.CRASHED, "ScheduledService: Task failed: " + crontab.getCronCommand());
            shutdown = true;
            return false;
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Task succeeded: " + crontab.getCronCommand());
            return true;
        }
    }

    /**
     * Minimal cron-like parser.
     *
     * @author eyedeekay
     * @since 1.8.0/0.9.54
     */
    private class CronTab {
        private String[] cron_entries;
        private boolean run_already;
        int[] match_now = new int[4];
        long last_run = 0;
        public CronTab(File configFile) throws FileNotFoundException {
            File myObj = new File(configFile.getAbsolutePath());
            Scanner myReader = new Scanner(myObj);
            String data = "";
            while (myReader.hasNextLine()) {
                data = myReader.nextLine();
            }
            myReader.close();
            if (data != "" && data != null) {
                // Split the string into an array of strings, based on the space OR the tab delimiter.
                String[] validate_cron_entries = data.split("[\\s\\t]+");
                // length of the array should be either 2 or 5.
                if (validate_cron_entries.length == 2 || validate_cron_entries.length == 5) {
                    cron_entries = validate_cron_entries;
                } else {
                    // if the length is greater than 6, then combine entries 5-end into one entry.
                    if (validate_cron_entries.length > 5) {
                        String[] trailing_cron_args = new String[validate_cron_entries.length - 5];
                        for (int i = 5; i < validate_cron_entries.length; i++) {
                            trailing_cron_args[i - 5] = validate_cron_entries[i];
                        }
                        String trailing_cron_entry = "";
                        for (int i = 0; i < trailing_cron_args.length; i++) {
                            trailing_cron_entry += trailing_cron_args[i] + " ";
                        }
                        trailing_cron_entry = trailing_cron_entry.trim();
                        String[] new_cron_entries = new String[5];
                        for (int i = 0; i < 5; i++) {
                            new_cron_entries[i] = validate_cron_entries[i];
                        }
                        new_cron_entries[5] = trailing_cron_entry;
                        cron_entries = new_cron_entries;
                    }
                }
            }else{
                if (_log.shouldLog(Log.WARN))
                    _log.warn("CronTab is empty, please check your config file.");
                throw new FileNotFoundException("Invalid cron entry in config file: " + configFile.getAbsolutePath() + " is empty.");
            }

        }
        private void checkCronCommand() {
            String command = getCronCommand().split(" ")[0];
            if (command == null || command.equals("")) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid cron entry in config file: " + getCronCommand());
                throw new InvalidParameterException("Invalid cron entry in config file: " + getCronCommand() + " Command cannot be empty");
            }
            // Check if we have an absolute path to a plugin directory
            // if so, make the first element executable if it exists
            if (command.contains(myPluginDir().getAbsolutePath())){
                File commandFile = new File(command);
                if (!commandFile.exists()){
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Invalid cron entry in config file: " + getCronCommand());
                    throw new InvalidParameterException("Invalid cron entry in config file: " + getCronCommand() + " does not exist");
                }
                if (!commandFile.canExecute()){
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Invalid cron entry in config file: " + getCronCommand());
                    commandFile.setExecutable(true);
                }
            }
        }
        public String getCronCommand() {
            checkCronCommand();
            // command is always the **last** entry in the array
            return cron_entries[cron_entries.length - 1].replaceAll("$PLUGIN", myPluginDir().getAbsolutePath());
        }
        public boolean TimeToRun() {
            //Date now_date = new Date(now);
            Calendar now_date = Calendar.getInstance();
            if (cron_entries.length > 4 ) {
                int now_minute = now_date.get(Calendar.MINUTE);
                int now_hour = now_date.get(Calendar.HOUR_OF_DAY);
                int now_day_month = now_date.get(Calendar.DAY_OF_MONTH);
                int now_month = now_date.get(Calendar.MONTH);
                int now_day_week = now_date.get(Calendar.DAY_OF_WEEK);

                String cron_minute = cron_entries[0];
                String cron_hour = cron_entries[1];
                String cron_day_month = cron_entries[2];
                String cron_month = cron_entries[3];
                String cron_day_week = cron_entries[4];
                if (matchCronEntry(now_minute, cron_minute, 0) &&
                    matchCronEntry(now_hour, cron_hour, 1) &&
                    matchCronEntry(now_day_month, cron_day_month, 2) &&
                    matchCronEntry(now_month, cron_month, 3) &&
                    matchCronEntry(now_day_week, cron_day_week, 4)) {
                    return true;
                }
            }
            if (cron_entries.length == 2) {
                if (matchCronEntry(cron_entries[0])) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchCronEntry(String cron_entry) {
            switch (cron_entry) {
                case "@monthly":
                    if (run_already) {
                        // has it been at least a month since last run?
                        if (System.currentTimeMillis() - last_run > (1000 * 60 * 60 * 24 * 30)) {
                            run_already = false;
                        }
                    }
                    // 40320 minutes in 28 days
                    // generate a random number between 0 and 40320
                    // if it is a zero or a 1, run the task
                    int rand_monthly = (int) (Math.random() * 40320);
                    if (rand_monthly < 2) {
                        run_already = true;
                        last_run = System.currentTimeMillis();
                        return true;
                    }
                    return false;
                case "@weekly":
                    // 10080 minutes in 7 days
                    // generate random number between 0 and 10080
                    // if it is a zero or a 1, run the task
                    if (run_already) {
                        // has it been at least a month since last run?
                        if (System.currentTimeMillis() - last_run > (1000 * 60 * 60 * 24 * 7)) {
                            run_already = false;
                        }
                    }
                    int rand_week = (int) (Math.random() * 10080);
                    if (rand_week < 2) {
                        run_already = true;
                        last_run = System.currentTimeMillis();
                        return true;
                    }
                    return false;
                case "@daily":
                    // 1440 minutes in 24 hours
                    // generate a random number between 0 and 1440
                    // if it is a zero or a 1, run the task
                    if (run_already) {
                        // has it been at least a month since last run?
                        if (System.currentTimeMillis() - last_run > (1000 * 60 * 60 * 24)) {
                            run_already = false;
                        }
                    }
                    int rand_minutes = (int) (Math.random() * 1440);
                    if (rand_minutes < 2) {
                        run_already = true;
                        last_run = System.currentTimeMillis();
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        private boolean matchCronEntry(int now_entry, String cron_entry, int index) {
            if (cron_entry.equals("*")) {
                return true;
            }
            if (cron_entry.contains("-")) {
                String[] cron_entry_range = cron_entry.split("-");
                int cron_entry_start = Integer.parseInt(cron_entry_range[0]);
                int cron_entry_end = Integer.parseInt(cron_entry_range[1]);
                if (now_entry >= cron_entry_start && now_entry <= cron_entry_end) {
                    if (run_already) {
                        return false;
                    }
                    if (match_now[index] == 0) {
                        // generate a random number between cron_entry_start and cron_entry_end, and store it until
                        // the next time the cron entry is matched.
                        match_now[index] = (int) (Math.random() * (cron_entry_end - cron_entry_start + 1)) + cron_entry_start;
                    }
                    if (match_now[index] == now_entry) {
                        run_already=true;
                        match_now[index] = 0;
                        return true;
                    }
                } else {
                    match_now[index] = 0;
                    run_already = false;
                }
            }
            if (cron_entry.contains("/")) {
                String[] cron_entry_split = cron_entry.split("/");
                int cron_entry_split_length = cron_entry_split.length;
                if (cron_entry_split_length != 2) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Invalid cron entry in config file: " + cron_entry);
                    return false;
                }
                int cron_entry_split_first = Integer.parseInt(cron_entry_split[0]);
                int cron_entry_split_second = Integer.parseInt(cron_entry_split[1]);
                if (cron_entry_split_first > cron_entry_split_second) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Invalid cron entry in config file: " + cron_entry);
                    return false;
                }
                if (now_entry >= cron_entry_split_first && now_entry <= cron_entry_split_second) {
                    return true;
                }
            } else {
                if (now_entry == Integer.parseInt(cron_entry)) {
                    return true;
                }
            }
            return false;
        }
    }
}
