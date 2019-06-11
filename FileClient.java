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
    private static final String ramDiskFile = "/tmp/munehiro.txt";
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
            FileClient.WritebackThread var1 = new FileClient.WritebackThread();
            var1.start();
            String var2 = null;
            String var3 = null;

            try {
                System.out.println("FileClient: Next file to open:");
                System.out.print("\tFile name: ");
                var2 = this.input.readLine();
                if (!var2.equals("quit") && !var2.equals("exit")) {
                    if (var2.equals("")) {
                        System.err.println("Do it again");
                        var1.kill();
                        continue;
                    }
                } else {
                    if (this.file.isStateWriteOwned()) {
                        this.file.upload();
                    }

                    System.exit(0);
                }

                System.out.print("\tHow(r/w): ");
                var3 = this.input.readLine();
                if (!var3.equals("r") && !var3.equals("w")) {
                    System.err.println("Do it again");
                    var1.kill();
                    continue;
                }
            } catch (IOException var5) {
                var5.printStackTrace();
            }

            var1.kill();
            if (!this.file.hit(var2, var3)) {
                if (this.file.isStateWriteOwned()) {
                    this.file.upload();
                }

                this.file.download(var2, var3);
            }

            this.file.launchEmacs(var3);
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
        private int state = 0;
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
            return this.state == 0;
        }

        public synchronized boolean isStateReadShared() {
            return this.state == 1;
        }

        public synchronized boolean isStateWriteOwned() {
            System.out.println("name = " + this.name + " state = " + this.state + " ownership = " + this.ownership);
            return this.state == 2;
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
                this.state = 3;
                return true;
            } else {
                return false;
            }
        }

        public synchronized boolean hit(String var1, String var2) {
            if (!this.name.equals(var1)) {
                System.out.println("file: " + var1 + " does not exist.");
                return false;
            } else if (this.state == 3) {
                System.out.println("file: " + var1 + " must be written back.");
                return false;
            } else if (var2.equals("r") && this.state != 0) {
                System.out.println("file: " + var1 + " exists for read.");
                return true;
            } else if (var2.equals("w") && this.state == 2) {
                System.out.println("file: " + var1 + " is owned for write");
                return true;
            } else {
                System.out.println("file: " + var1 + " accessed with " + var2);
                return false;
            }
        }

        public boolean download(String var1, String var2) {
            System.out.println("downloading: " + var1 + " with " + var2 + " mode");
            synchronized(this) {
                switch(this.state) {
                    case 0:
                        if (var2.equals("r")) {
                            this.state = 1;
                        } else if (var2.equals("w")) {
                            this.state = 2;
                        }
                        break;
                    case 1:
                        if (var2.equals("w")) {
                            this.state = 2;
                        }
                }
            }

            this.name = var1;
            this.ownership = var2.equals("w");

            try {
                FileContents var3 = FileClient.this.server.download(this.myIpName, this.name, var2);
                this.bytes = var3.get();
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
                FileClient.this.server.upload(this.myIpName, this.name, var1);
            } catch (RemoteException var3) {
                var3.printStackTrace();
                return false;
            }

            System.out.println("uploading: " + this.name + " completed");
            return true;
        }

        private boolean execUnixCommand(String var1, String var2, String var3) {
            String[] var4 = var3.equals("") ? new String[2] : new String[3];
            var4[0] = var1;
            var4[1] = var2;
            if (!var3.equals("")) {
                var4[2] = var3;
            }

            try {
                Runtime var5 = Runtime.getRuntime();
                Process var6 = var5.exec(var4);
                int var7 = var6.waitFor();
                return true;
            } catch (IOException var8) {
                var8.printStackTrace();
                return false;
            } catch (InterruptedException var9) {
                var9.printStackTrace();
                return false;
            }
        }

        public boolean launchEmacs(String var1) {
            if (!this.execUnixCommand("chmod", "600", "/tmp/munehiro.txt")) {
                return false;
            } else {
                try {
                    FileOutputStream var2 = new FileOutputStream("/tmp/munehiro.txt");
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

                if (!this.execUnixCommand("chmod", var1.equals("r") ? "400" : "600", "/tmp/munehiro.txt")) {
                    return false;
                } else {
                    boolean var8 = this.execUnixCommand("emacs", "/tmp/munehiro.txt", "");
                    if (var8 && var1.equals("w")) {
                        try {
                            FileInputStream var3 = new FileInputStream("/tmp/munehiro.txt");
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