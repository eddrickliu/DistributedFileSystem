
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Vector;

public class FileServer extends UnicastRemoteObject implements ServerInterface {
    private String port = null;
    private Vector<FileServer.File> cache = null;

    public FileServer(String var1) throws RemoteException {
        this.port = var1;
        this.cache = new Vector();
    }

    public static void main(String[] var0) {
        if (var0.length != 1) {
            System.err.println("usage: java FileServer port#");
            System.exit(-1);
        }

        try {
            FileServer var1 = new FileServer(var0[0]);
            Naming.rebind("rmi://localhost:" + var0[0] + "/fileserver", var1);
            System.out.println("FileServer running on port " + var0[0]);
        } catch (Exception var2) {
            var2.printStackTrace();
            System.exit(1);
        }

    }

    public FileContents download(String var1, String var2, String var3) {
        FileServer.File var4 = null;
        synchronized(this.cache) {
            int var6 = 0;

            while(true) {
                if (var6 < this.cache.size()) {
                    var4 = (FileServer.File)this.cache.elementAt(var6);
                    if (!var4.hit(var2)) {
                        var4 = null;
                        ++var6;
                        continue;
                    }
                }

                if (var4 == null) {
                    var4 = new FileServer.File(var2, this.port);
                    this.cache.add(var4);
                }
                break;
            }
        }

        return var4.download(var1, var3);
    }

    /**
     * FileServer::upload()
     */
    public boolean upload(String client, String fileName, FileContents contents) {
//    	System.out.println("FileServer::upload()");    delete	
        FileServer.File file = null;
        synchronized(this.cache) {
        	for(int i = 0; i < this.cache.size(); i++) {
        		System.out.println("Searching cache for the file");
                file = (File)this.cache.elementAt(i);
                if (file.hit(fileName)) {
                	System.out.println("File was found in cache");
                	break;
                }else{
//                	System.out.println("Checked a cache file, not a match");  DELETE
                    file = null;
                    continue;
                }
            }
        }

        System.out.println(client + "Uploading " + file);
        return file == null ? false : file.upload(client, contents);
    }

    private class File {
        private static final int state_notshared = 0;
        private static final int state_readshared = 1;
        private static final int state_writeshared = 2;
        private static final int state_back2writeshared = 3;
        private int state = 0;
        private String name;
        private byte[] bytes = null;
        private Vector<String> readers = null;
        private String owner = null;
        private String port = null;
        private Object inStateBack2WriteShared = null;

        private boolean removeReader(String var1) {
            for(int var2 = 0; var2 < this.readers.size(); ++var2) {
                String var3 = (String)this.readers.elementAt(var2);
                if (var1.equals(var3)) {
                    this.readers.remove(var2);
                    return true;
                }
            }

            return false;
        }

        private void listReaders() {
            System.out.println("# readers = " + this.readers.size());

            for(int var1 = 0; var1 < this.readers.size(); ++var1) {
                String var2 = (String)this.readers.elementAt(var1);
                System.out.println("\treader = " + var2);
            }

        }

        private void printTransition(String var1, String var2, String mode, int oldState, int newState, int errorCode) {
            System.out.println("file(" + this.name + ") requested by " + var1 + ":" + var2 + "(" + mode + "): state( " + 
            		(oldState == 0 ? "notshared" : (oldState == 1 ? "readshared" : (oldState == 2 ? "writeshared" : 
            		"back2writeshared"))) + " --> " + (newState == 0 ? "notshared" : (newState == 1 ? "readshared" : 
            		(newState == 2 ? "writeshared" : "back2writeshared"))) + " )  error_code = " + errorCode);
            this.listReaders();
            System.out.println("owner = " + this.owner);
        }

        public File(String var2, String var3) {
            this.name = var2;
            this.readers = new Vector();
            this.owner = null;
            this.port = var3;
            this.inStateBack2WriteShared = new Object();
            this.bytes = this.fileRead();
        }

        private byte[] fileRead() {
            Object var1 = null;

            byte[] var5;
            try {
                FileInputStream var2 = new FileInputStream(this.name);
                var5 = new byte[var2.available()];
                var2.read(var5);
                var2.close();
            } catch (FileNotFoundException var3) {
                var3.printStackTrace();
                return null;
            } catch (IOException var4) {
                var4.printStackTrace();
                return null;
            }

            System.out.println("file read from " + this.name + ": " + var5.length + " bytes");
            return var5;
        }

        private boolean fileWrite() {
            try {
                FileOutputStream var1 = new FileOutputStream(this.name);
                var1.write(this.bytes);
                var1.flush();
                var1.close();
            } catch (FileNotFoundException var2) {
                var2.printStackTrace();
                return false;
            } catch (IOException var3) {
                var3.printStackTrace();
                return false;
            }

            System.out.println("file written to " + this.name + ": " + this.bytes.length + " bytes");
            return true;
        }

        /**
         * FileServer::File::download()
         * @param client
         * @param mode
         * @return
         */
        public synchronized FileContents download(String client, String mode) {
            if (this.name.equals("")) {
                return null;
            } else {
                while(this.state == 3) {
                    synchronized(this.inStateBack2WriteShared) {
                        try {
                            System.out.println(client + "now wait on inStateBack2WriteShared");
                            this.inStateBack2WriteShared.wait();
                        } catch (InterruptedException var12) {
                            var12.printStackTrace();
                        }
                    }
                }

                int var3 = this.state;
                byte var4 = 0;
                
                // State transition
                switch(this.state) {
                    case 0:
                        if (mode.equals("r")) {
                            this.state = 1;
                            this.readers.add(client);
                        } else if (mode.equals("w")) {
                            this.state = 2;
                            if (this.owner != null) {
                                var4 = 1;
                            } else {
                                this.owner = client;
                            }
                        } else {
                            var4 = 2;
                        }
                        break;
                    case 1:
                        this.removeReader(client);
                        if (mode.equals("r")) {
                            this.readers.add(client);
                        } else if (mode.equals("w")) {
                            this.state = 2;
                            if (this.owner != null) {
                                var4 = 3;
                            } else {
                                this.owner = client;
                            }
                        } else {
                            var4 = 4;
                        }
                        break;
                    case 2:
                        this.removeReader(client);
                        if (mode.equals("r")) {
                            this.readers.add(client);
                        } else if (mode.equals("w")) {
                            this.state = 3;
                            ClientInterface var5 = null;

                            try {
                                var5 = (ClientInterface)Naming.lookup("rmi://" + this.owner + ":" + this.port + "/fileclient");
                            } catch (Exception var11) {
                                var11.printStackTrace();
                                var4 = 5;
                            }

                            if (var5 != null) {
                                try {
                                    var5.writeback();
                                } catch (RemoteException var10) {
                                    var10.printStackTrace();
                                    var4 = 6;
                                }

                                System.out.println("download( " + this.name + " ): " + this.owner + "'s copy was invalidated");
                            }

                            if (var4 == 0) {
                                try {
                                    System.out.println("download " + this.name + " ): " + client + " waits for writeback");
                                    this.wait();
                                } catch (InterruptedException var9) {
                                    var9.printStackTrace();
                                    var4 = 7;
                                }

                                this.owner = client;
                            }
                        } else {
                            var4 = 8;
                        }
                }

                // 
                this.printTransition(client, "download", mode, var3, this.state, var4);
                if (var4 > 0) {
                    return null;
                } else {
                    FileContents var14 = new FileContents(this.bytes);
                    if (var3 == 3) {
                        synchronized(this.inStateBack2WriteShared) {
                            this.inStateBack2WriteShared.notifyAll();
                            System.out.println(client + " woke up all waiting on inStateBack2WriteShared");
                        }
                    }

                    return var14;
                }
            }
        }

        
        /**
         * 
         * @param client
         * @param contents
         * @return
         */
        public synchronized boolean upload(String client, FileContents contents) {
        	System.out.println("FileServer::File::upload()");
            boolean var3 = this.name.equals("");
            int var10 = this.state != 0 && this.state != 1 ? 0 : 2;

            int var4;
            for(var4 = 0; var10 == 0 && var4 < this.readers.size(); ++var4) {
                String var5 = (String)this.readers.elementAt(var4);
                ClientInterface var6 = null;

                try {
                    var6 = (ClientInterface)Naming.lookup("rmi://" + var5 + ":" + this.port + "/fileclient");
                } catch (Exception var9) {
                    var9.printStackTrace();
                    var10 = 3;
                }

                if (var6 != null) {
                    try {
                        var6.invalidate();
                    } catch (RemoteException var8) {
                        var8.printStackTrace();
                    }

                    System.out.println("update( " + this.name + " ): " + var5 + "'s copy was invalidated");
                }
            }

            this.readers.removeAllElements();
            var4 = this.state;
            if (var10 == 0) {
                this.bytes = contents.get();
                System.out.println("bytes = " + new String(this.bytes));
                switch(this.state) {
                    case 2:
                        this.state = 0;
                        this.owner = null;
                        var10 = !this.fileWrite() ? 4 : 0;
                        break;
                    case 3:
                        this.state = 2;
                        this.owner = client;
                        this.notify();
                }
            }

            this.printTransition(client, "upload", "w", var4, this.state, var10);
            return var10 == 0;
        }

        public synchronized boolean isStateNotShared() {
            return this.state == 0;
        }

        public synchronized boolean isStateReadShared() {
            return this.state == 1;
        }

        public synchronized boolean isStateWriteShared() {
            return this.state == 2;
        }

        public synchronized boolean isStatebackToWriteShared() {
            return this.state == 3;
        }

        /**
         * Does this file's name match the passed filename?
         * @param fileName
         * @return
         */
        public synchronized boolean hit(String fileName) {
            return this.name.equals(fileName);
        }
    }
}
