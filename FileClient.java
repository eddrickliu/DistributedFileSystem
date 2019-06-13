import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

/**
 * The file client class can be ran many times for one instance of this program, the
 * will communicate with the central File Server class. Uses the RMI java classes
 * to accomplish method execution on different machines
 */
public class FileClient extends UnicastRemoteObject implements ClientInterface {
    //instance variables
    private BufferedReader input = null;
    private static final String ramDiskFile = "/tmp/ggoziker_css434.txt";
    private ServerInterface server = null;
    private FileClient.File file = null;

    /**
     * File Client argument constructor, this will establish a connection with a given server
     * as well as set all relevant instance variables
     *
     * @param machineName       String that describes the machineName of the server that
     *                          this client is trying to connect to.
     * @param port              Stirng that describes the port number shared with clients
     *                          and Server
     * @throws RemoteException  Needed for java RMI class
     */
    public FileClient(String machineName, String port) throws RemoteException {
        try {
            this.server = (ServerInterface)Naming.lookup("rmi://"
                    + machineName + ":" + port + "/fileserver");
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.file = new FileClient.File();
        this.input = new BufferedReader(new InputStreamReader(System.in));
    }

    /**
     * Looper method to keep receiving inputs through the command line and ask the user
     * to enter a file name as well as what access mode the user desires to use
     */
    public void loop() {
        while(true) {
            FileClient.WritebackThread writebackThread = new FileClient.WritebackThread();
            writebackThread.start();
            String name = null;
            String state = null;

            try {
                System.out.println("Enter name of file to open:");
                name = this.input.readLine();
                if (!name.equals("quit") && !name.equals("exit")) {
                    if (name.equals("")) {
                        System.err.println("Invalid input");
                        System.out.print("Enter name of file to open: ");
                        writebackThread.kill();
                        continue;
                    }
                } else {
                    if (this.file.isStateWriteOwned()) {
                        this.file.upload();
//                        System.out.println("After upload1");		// @TODO delete
                    }

                    System.exit(0);
                }

                System.out.print("Enter access mode (r/w): ");
                state = this.input.readLine();
                if (!state.equals("r") && !state.equals("w")) {
                    System.err.println("Invalid input.");
                    System.out.print("Enter access mode (r/w): ");
                    writebackThread.kill();
                    continue;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            writebackThread.kill();
            // Check if 
            if (!this.file.hit(name, state)) {
                if (this.file.isStateWriteOwned()) {
                    this.file.upload();
//                    System.out.println("After upload2");		// @TODO delete
                }

                this.file.download(name, state);
            }

            this.file.launchEmacs(state);
        }
    }

    /**
     * Getter method to get this file's state of being invalid
     * @return returns true if invalidated false if not
     */
    public boolean invalidate() {
        return this.file.invalidate();
    }

    /**
     * Getter method to get this file's state of writeback
     * @return true if back2readshared false if not
     */
    public boolean writeback() {
        return this.file.writeback();
    }

    /**
     * Main method to take in command line arguments through the given terminal.
     * Creates a new object as well as uses the rebind method in RMI to get the
     * Flle client on rmi registry
     *
     * @param args an array of strings that are the commandline args
     *             arg0: machineName of server
     *             arg1: port
     */
    public static void main(String[] args) {
    	// Check arguments
        if (args.length != 2) {
            System.err.println("usage: java FileClient server_ip port#");
            System.exit(-1);
        }

        try {
            FileClient client = new FileClient(args[0], args[1]);
            Naming.rebind("rmi://localhost:" + args[1] + "/fileclient", client);
            System.out.println("FileClient running on port " + args[0]);
            client.loop();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

    }

    /**
     * Private inner class file to help with defining a specific file that is being
     * used right now. Many different states: invalid, readshared,
     * writeowned, back2readshared.
     */
    private class File {
        private static final int state_invalid = 0;
        private static final int state_readshared = 1;
        private static final int state_writeowned = 2;
        private static final int state_back2readshared = 3;
        /*
         * State is 2 if it's in write mode
         */
        private int state = state_invalid;
        private boolean inEdit = false;
        private String name = "";
        private boolean ownership = false;
        private byte[] bytes = null;
        private String myIpName = null;

        /**
         * No arg constructor that sets the file's location
         * relative to all the client's by
         * taking the localHost using Inet.
         */
        public File() {
            try {
                InetAddress localHost = InetAddress.getLocalHost();
                this.myIpName = localHost.getHostName();
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }

        }

        /**
         * getter method to see if the state is invalid. compares the current
         * state to the previously set final boolean state_invalid
         *
         * @return true if invalid, false if not
         */
        public synchronized boolean isStateInvalid() {
            return this.state == state_invalid;
        }

        /**
         * getter method to see if the state is readshared. compares the current
         * state to the previously set final boolean state_readshared
         *
         * @return true if readshared, false if not
         *
         */
        public synchronized boolean isStateReadShared() {
            return this.state == state_readshared;
        }


        /**
         * getter method to see if the state is writeowned. compares the current
         * state to the previously set final boolean state_writeowned
         *
         * @return true if writeowned, false if not
         *
         */
        public synchronized boolean isStateWriteOwned() {
            System.out.println("File: " + this.name + ", state: " + this.state
                    + ", ownership:" + this.ownership);
            return this.state == state_writeowned;
        }


        /**
         * getter method to see if the state is back2readShared. compares the current
         * state to previously set final boolean back2ReadShared.
         *
         * @return true if isStateBackToReadShared, false if not
         *
         */
        public synchronized boolean isStateBackToReadShared() {
            return this.state == state_back2readshared;
        }

        /**
         * getter method to see if this file is invalidated
         *
         * @return true if invalidated, false if not
         */
        public synchronized boolean invalidate() {
            if (this.state == 1) {
                this.state = 0;
                System.out.println("File " + this.name + " invalidated. State is now " + this.state);
                return true;
            } else {
                return false;
            }
        }

        /**
         * getter method to see if this file is written back to
         *
         * @return true if written back false if not
         */
        public synchronized boolean writeback() {
            if (this.state == 2) {
                this.state = state_back2readshared;
                return true;
            } else {
                return false;
            }
        }

        /**
         * Method to check the validity of targetting the given file. Will be used in main
         * class as a way to continue or not.
         *
         * @param name String the is the name of the file
         * @param state String that described the files state.
         * @return true if the passed file name and state are valid for access false if not.
         */
        public synchronized boolean hit(String name, String state) {
            if (!this.name.equals(name)) {
                System.out.println("File " + name + " does not exist.");
                return false;
            } else{
            	if (this.state == 3) {
	                System.out.println("File " + name + " needs to be written back.");
	                return false;
	            } else if (state.equals("r") && this.state != 0) {
	                System.out.println("File " + name + " exists for read.");
	                return true;
	            } else if (state.equals("w") && this.state == 2) {
	                System.out.println("File " + name + " is owned for write");
	                return true;
	            } else {
	                System.out.println("File " + name + " accessed with " + state);
	                return false;
	            }
            }
        }

        /**
         * Method to download a file into this method's file contents.
         *
         * @param name  String representing the name of this file
         * @param mode  String representing the mode of this file
         * @return true if an exception is occured through this process
         *          false if not
         */
        public boolean download(String name, String mode) {
            System.out.println("Downloading " + name + " with " + mode + " mode");
            synchronized(this) {
                switch(this.state) {
                    case 0:
                        if (mode.equals("r")) {
                            this.state = 1;
                        } else if (mode.equals("w")) {
                            this.state = 2;
                        }
                        break;
                    case 1:
                        if (mode.equals("w")) {
                            this.state = 2;
                        }
                }
            }

            this.name = name;
            this.ownership = mode.equals("w");

            try {
                FileContents contents = server.download(myIpName, name, mode);
                this.bytes = contents.get();
                return true;
            } catch (RemoteException e) {
                e.printStackTrace();
                return false;
            }
        }

        /**
         * Method to upload a file to the server, this method uses the RMI
         * class to call the server's upload method.
         *
         * @return true if an exception is occured through this process
         *          false if not
         */
        public boolean upload() {
            System.out.println("Uploading: " + this.name);
            synchronized(this) {
                switch(this.state) {
                    case 2:
                        this.state = 0;
                        break;
                    case 3:
                        this.state = 1;
                }
            }

            FileContents contents = new FileContents(this.bytes);

            try {
//            	System.out.println("FileClient::upload() -- about to call \"FileClient.this.server.upload(this.myIpName, this.name, var1);\"");			DELETE
                server.upload(this.myIpName, this.name, contents);
//            	System.out.println("FileClient::upload() -- finished calling \"FileClient.this.server.upload(this.myIpName, this.name, var1);\"");		DELETE
            } catch (RemoteException e) {
                e.printStackTrace();
                return false;
            }

            System.out.println("Done uploading " + this.name);
            return true;
        }

        /**
         * Method to execute a unix command with given inputs.
         * Used primarliy for launching emacs on the client.
         *
         * @param cmd the command that is given to the method
         * @param arg1 first input argument for the command
         * @param arg2 second input argument of the command
         * @return returns false if there are any exceptions that are occured
         */
        private boolean execUnixCommand(String cmd, String arg1, String arg2) {
            String[] args = arg2.equals("") ? new String[2] : new String[3];
            args[0] = cmd;
            args[1] = arg1;
            if (!arg2.equals("")) {
                args[2] = arg2;
            }

            try {
                Runtime runtime = Runtime.getRuntime();
                Process process = runtime.exec(args);
                int retval = process.waitFor();
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            } catch (InterruptedException e) {
                e.printStackTrace();
                return false;
            }
        }

        /**
         *
         * Method to launchEmacs on this client uses the exec unixcommand class to achieve
         * this goal
         *
         * @param mode a String representing the mode of which Emacs will be launched
         * @return returns false if any exepcetion occurs during the process
         */
        public boolean launchEmacs(String mode) {
            if (!this.execUnixCommand("chmod", "600", "/tmp/ggoziker_css434.txt")) {
                return false;
            } else {
                try {
                    FileOutputStream stream = new FileOutputStream("/tmp/ggoziker_css434.txt");
                    stream.write(this.bytes);
                    stream.flush();
                    stream.close();
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                    return false;
                } catch (IOException e) {
                    e.printStackTrace();
                    return false;
                }

                // Set file's access mode to 400 if mode==read, to 600 if mode==write
                if (!this.execUnixCommand("chmod", mode.equals("r") ? "400" : "600", "/tmp/ggoziker_css434.txt")) {
                    return false;
                } else {
                    boolean emacsLaunched = this.execUnixCommand("emacs", "/tmp/ggoziker_css434.txt", "");
//                    
                    System.out.println("Launched emacs");
//                    
                    if (emacsLaunched && mode.equals("w")) {
                        try {
                            FileInputStream stream = new FileInputStream("/tmp/ggoziker_css434.txt");
                            this.bytes = new byte[stream.available()];
                            stream.read(this.bytes);
                            stream.close();
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                            return false;
                        } catch (IOException e) {
                            e.printStackTrace();
                            return false;
                        }
                    }

                    return true;
                }
            }
        }
    }

    /**
     * Helper class to create different threads of the client writeback process
     * usually gets called when exit happens and file is uploaded. 
     *
     */
    private class WritebackThread extends Thread {
        //instance variable
        private boolean active = false;

        /**
         * Argument constructor to set instance variable active to true
         */
        public WritebackThread() {
            this.active = true;
        }

        /**
         * Run method that automatically gets called when the thread is created.
         */
        public void run() {
            while(this.isActive()) {
                if (FileClient.this.file.isStateBackToReadShared()) {
                    FileClient.this.file.upload();
//                    System.out.println("After upload3");		// @TODO delete
                }
            }

        }

        /**
         * Kills this thread and joins the rest, synchronized
         */
        synchronized void kill() {
            this.active = false;

            try {
                this.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }

        /**
         * getter method to get the status of this thread.
         *
         *
         * @return the status of this thread
         */
        synchronized boolean isActive() {
            return this.active;
        }
    }
}