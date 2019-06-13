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

public class FileClient extends UnicastRemoteObject implements ClientInterface {
    private BufferedReader input = null;
    private static final String ramDiskFile = "/tmp/ggoziker_css434.txt";
    private ServerInterface server = null;
    private FileClient.File file = null;

    public FileClient(String machineName, String port) throws RemoteException {
        try {
            this.server = (ServerInterface)Naming.lookup("rmi://" + machineName + ":" + port + "/fileserver");
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.file = new FileClient.File();
        this.input = new BufferedReader(new InputStreamReader(System.in));
    }

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

    public boolean invalidate() {
        return this.file.invalidate();
    }

    public boolean writeback() {
        return this.file.writeback();
    }

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

        public File() {
            try {
                InetAddress localHost = InetAddress.getLocalHost();
                this.myIpName = localHost.getHostName();
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }

        }

        public synchronized boolean isStateInvalid() {
            return this.state == state_invalid;
        }

        public synchronized boolean isStateReadShared() {
            return this.state == state_readshared;
        }

        public synchronized boolean isStateWriteOwned() {
            System.out.println("File: " + this.name + ", state: " + this.state + ", ownership:" + this.ownership);
            return this.state == state_writeowned;
        }

        public synchronized boolean isStateBackToReadShared() {
            return this.state == 3;
        }

        public synchronized boolean invalidate() {
            if (this.state == 1) {
                this.state = 0;
                System.out.println("File " + this.name + " invalidated. State is now " + this.state);
                return true;
            } else {
                return false;
            }
        }

        public synchronized boolean writeback() {
            if (this.state == 2) {
                this.state = state_back2readshared;
                return true;
            } else {
                return false;
            }
        }

        /**
         * Return true if the passed file name and state are valid for access
         * @param name
         * @param state
         * @return
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

    private class WritebackThread extends Thread {
        private boolean active = false;

        public WritebackThread() {
            this.active = true;
        }

        public void run() {
            while(this.isActive()) {
                if (FileClient.this.file.isStateBackToReadShared()) {
                    FileClient.this.file.upload();
//                    System.out.println("After upload3");		// @TODO delete
                }
            }

        }

        synchronized void kill() {
            this.active = false;

            try {
                this.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }

        synchronized boolean isActive() {
            return this.active;
        }
    }
}