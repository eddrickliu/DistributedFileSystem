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

    public FileClient(String var1, String var2) throws RemoteException {
        try {
            this.server = (ServerInterface)Naming.lookup("rmi://" + var1 + ":" + var2 + "/fileserver");
        } catch (Exception var4) {
            var4.printStackTrace();
        }

        this.file = new FileClient.File();
        this.input = new BufferedReader(new InputStreamReader(System.in));
    }

    public void loop() {
        while(true) {
            FileClient.WritebackThread writebackThread = new FileClient.WritebackThread();
            writebackThread.start();
            String var2 = null;
            String state = null;

            try {
                System.out.println("FileClient: Next file to open:");
                System.out.print("\tFile name: ");
                var2 = this.input.readLine();
                if (!var2.equals("quit") && !var2.equals("exit")) {
                    if (var2.equals("")) {
                        System.err.println("Do it again");
                        writebackThread.kill();
                        continue;
                    }
                } else {
                    if (this.file.isStateWriteOwned()) {
                        this.file.upload();
                        System.out.println("After upload1");		// @TODO delete
                    }

                    System.exit(0);
                }

                System.out.print("\tHow(r/w): ");
                state = this.input.readLine();
                if (!state.equals("r") && !state.equals("w")) {
                    System.err.println("Do it again");
                    writebackThread.kill();
                    continue;
                }
            } catch (IOException var5) {
                var5.printStackTrace();
            }

            writebackThread.kill();
            // Check if 
            if (!this.file.hit(var2, state)) {
                if (this.file.isStateWriteOwned()) {
                    this.file.upload();
                    System.out.println("After upload2");		// @TODO delete
                }

                this.file.download(var2, state);
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

    public static void main(String[] var0) {
        if (var0.length != 2) {
            System.err.println("usage: java FileClient server_ip port#");
            System.exit(-1);
        }

        try {
            FileClient var1 = new FileClient(var0[0], var0[1]);
            Naming.rebind("rmi://localhost:" + var0[1] + "/fileclient", var1);
            System.out.println("rmi://localhost: " + var0[0] + "/fileclient invokded");
            var1.loop();
        } catch (Exception var2) {
            var2.printStackTrace();
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
                InetAddress var2 = InetAddress.getLocalHost();
                this.myIpName = var2.getHostName();
            } catch (UnknownHostException var3) {
                var3.printStackTrace();
            }

        }

        public synchronized boolean isStateInvalid() {
            return this.state == state_invalid;
        }

        public synchronized boolean isStateReadShared() {
            return this.state == state_readshared;
        }

        public synchronized boolean isStateWriteOwned() {
            System.out.println("name = " + this.name + " state = " + this.state + " ownership = " + this.ownership);
            return this.state == state_writeowned;
        }

        public synchronized boolean isStateBackToReadShared() {
            return this.state == 3;
        }

        public synchronized boolean invalidate() {
            if (this.state == 1) {
                this.state = 0;
                System.out.println("file( " + this.name + ") invalidated...state " + this.state);
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
                System.out.println("file: " + name + " does not exist.");
                return false;
            } else if (this.state == 3) {
                System.out.println("file: " + name + " must be written back.");
                return false;
            } else if (state.equals("r") && this.state != 0) {
                System.out.println("file: " + name + " exists for read.");
                return true;
            } else if (state.equals("w") && this.state == 2) {
                System.out.println("file: " + name + " is owned for write");
                return true;
            } else {
                System.out.println("file: " + name + " accessed with " + state);
                return false;
            }
        }

        public boolean download(String name, String mode) {
            System.out.println("downloading: " + name + " with " + mode + " mode");
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
            } catch (RemoteException var5) {
                var5.printStackTrace();
                return false;
            }
        }

        public boolean upload() {
            System.out.println("uploading: " + this.name + " start");
            synchronized(this) {
                switch(this.state) {
                    case 2:
                        this.state = 0;
                        break;
                    case 3:
                        this.state = 1;
                }
            }

            FileContents var1 = new FileContents(this.bytes);

            try {
            	System.out.println("FileClient::upload() -- about to call \"FileClient.this.server.upload(this.myIpName, this.name, var1);\"");
                server.upload(this.myIpName, this.name, var1);
            	System.out.println("FileClient::upload() -- finished calling \"FileClient.this.server.upload(this.myIpName, this.name, var1);\"");
            } catch (RemoteException var3) {
                var3.printStackTrace();
                return false;
            }

            System.out.println("uploading: " + this.name + " completed");
            return true;
        }

        private boolean execUnixCommand(String cmd, String arg1, String arg2) {
            String[] var4 = arg2.equals("") ? new String[2] : new String[3];
            var4[0] = cmd;
            var4[1] = arg1;
            if (!arg2.equals("")) {
                var4[2] = arg2;
            }

            try {
                Runtime runtime = Runtime.getRuntime();
                Process process = runtime.exec(var4);
                int retval = process.waitFor();
                return true;
            } catch (IOException var8) {
                var8.printStackTrace();
                return false;
            } catch (InterruptedException var9) {
                var9.printStackTrace();
                return false;
            }
        }

        public boolean launchEmacs(String mode) {
            if (!this.execUnixCommand("chmod", "600", "/tmp/ggoziker_css434.txt")) {
                return false;
            } else {
                try {
                    FileOutputStream var2 = new FileOutputStream("/tmp/ggoziker_css434.txt");
                    var2.write(this.bytes);
                    var2.flush();
                    var2.close();
                } catch (FileNotFoundException var6) {
                    var6.printStackTrace();
                    return false;
                } catch (IOException var7) {
                    var7.printStackTrace();
                    return false;
                }

                // Set file's access mode to 400 if mode==read, to 600 if mode==write
                if (!this.execUnixCommand("chmod", mode.equals("r") ? "400" : "600", "/tmp/ggoziker_css434.txt")) {
                    return false;
                } else {
                    boolean var8 = this.execUnixCommand("emacs", "/tmp/ggoziker_css434.txt", "");
//                    
                    System.out.println("Launched emacs");
//                    
                    if (var8 && mode.equals("w")) {
                        try {
                            FileInputStream var3 = new FileInputStream("/tmp/ggoziker_css434.txt");
                            this.bytes = new byte[var3.available()];
                            var3.read(this.bytes);
                            var3.close();
                        } catch (FileNotFoundException var4) {
                            var4.printStackTrace();
                            return false;
                        } catch (IOException var5) {
                            var5.printStackTrace();
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
                    System.out.println("After upload3");		// @TODO delete
                }
            }

        }

        synchronized void kill() {
            this.active = false;

            try {
                this.join();
            } catch (InterruptedException var2) {
                var2.printStackTrace();
            }

        }

        synchronized boolean isActive() {
            return this.active;
        }
    }
}