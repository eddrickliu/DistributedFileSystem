
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

    public FileServer(String port) throws RemoteException {
        this.port = port;
        this.cache = new Vector();
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: java FileServer port#");
            System.exit(-1);
        }

        try {
            FileServer server = new FileServer(args[0]);
            Naming.rebind("rmi://localhost:" + args[0] + "/fileserver", server);
            System.out.println("FileServer running on port " + args[0]);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

    }

    public FileContents download(String client, String name, String mode) {
        FileServer.File file = null;
        synchronized(this.cache) {
            int i = 0;

            while(true) {
                if (i < this.cache.size()) {
                    file = (FileServer.File)this.cache.elementAt(i);
                    if (!file.hit(name)) {
                        file = null;
                        ++i;
                        continue;
                    }
                }

                if (file == null) {
                    file = new FileServer.File(name, this.port);
                    this.cache.add(file);
                }
                break;
            }
        }

        return file.download(client, mode);
    }

    /**
     * FileServer::upload()
     */
    public boolean upload(String client, String fileName, FileContents contents) {
//    	System.out.println("FileServer::upload()");    delete	
        FileServer.File file = null;
        synchronized(this.cache) {
        	for(int i = 0; i < this.cache.size(); i++) {
                file = (File)this.cache.elementAt(i);
                if (file.hit(fileName)) {
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

        private boolean removeReader(String readerToRemove) {
            for(int index = 0; index < this.readers.size(); ++index) {
                String readerInList = (String)this.readers.elementAt(index);
                if (readerToRemove.equals(readerInList)) {
                    this.readers.remove(index);
                    return true;
                }
            }

            return false;
        }

        private void listReaders() {
            System.out.println("Number of readers: " + this.readers.size());

            for(int i = 0; i < this.readers.size(); ++i) {
                String reader = (String)this.readers.elementAt(i);
                System.out.println("\tReader: " + reader);
            }

        }

        private void printTransition(String client, String downloadOrUpload, String mode, int oldState, int newState, int errorCode) {
            System.out.println(this.name + " requested by " + client + " for " + downloadOrUpload + "(" + mode + "). State( " + 
            		(oldState == 0 ? "notshared" : (oldState == 1 ? "readshared" : (oldState == 2 ? "writeshared" : 
            		"back2writeshared"))) + " --> " + (newState == 0 ? "notshared" : (newState == 1 ? "readshared" : 
            		(newState == 2 ? "writeshared" : "back2writeshared"))) + " ). Error code: " + errorCode);
            this.listReaders();
            System.out.println("Owner: " + this.owner);
        }

        public File(String name, String port) {
            this.name = name;
            this.readers = new Vector();
            this.owner = null;
            this.port = port;
            this.inStateBack2WriteShared = new Object();
            this.bytes = this.fileRead();
        }

        private byte[] fileRead() {

            byte[] buffer;
            try {
                FileInputStream stream = new FileInputStream(this.name);
                buffer = new byte[stream.available()];
                stream.read(buffer);
                stream.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return null;
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }

            System.out.println(buffer.length + " bytes read from " + this.name);
            return buffer;
        }

        private boolean fileWrite() {
            try {
                FileOutputStream outputStream = new FileOutputStream(this.name);
                outputStream.write(this.bytes);
                outputStream.flush();
                outputStream.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return false;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            System.out.println(this.bytes.length + " bytes written to " + this.name);
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
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }

                int oldState = this.state;
                byte errorCode = 0;
                
                // State transition
                switch(this.state) {
                    case 0:
                        if (mode.equals("r")) {
                            this.state = 1;
                            this.readers.add(client);
                        } else if (mode.equals("w")) {
                            this.state = 2;
                            if (this.owner != null) {
                                errorCode = 1;
                            } else {
                                this.owner = client;
                            }
                        } else {
                            errorCode = 2;
                        }
                        break;
                    case 1:
                        this.removeReader(client);
                        if (mode.equals("r")) {
                            this.readers.add(client);
                        } else if (mode.equals("w")) {
                            this.state = 2;
                            if (this.owner != null) {
                                errorCode = 3;
                            } else {
                                this.owner = client;
                            }
                        } else {
                            errorCode = 4;
                        }
                        break;
                    case 2:
                        this.removeReader(client);
                        if (mode.equals("r")) {
                            this.readers.add(client);
                        } else if (mode.equals("w")) {
                            this.state = 3;
                            ClientInterface clientInterface = null;

                            try {
                                clientInterface = (ClientInterface)Naming.lookup("rmi://" + this.owner + ":" + this.port + "/fileclient");
                            } catch (Exception e) {
                                e.printStackTrace();
                                errorCode = 5;
                            }

                            if (clientInterface != null) {
                                try {
                                    clientInterface.writeback();
                                } catch (RemoteException e) {
                                    e.printStackTrace();
                                    errorCode = 6;
                                }

                                System.out.println("download( " + this.name + " ): " + this.owner + "'s copy was invalidated");
                            }

                            if (errorCode == 0) {
                                try {
                                    System.out.println("download " + this.name + " ): " + client + " waits for writeback");
                                    this.wait();
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                    errorCode = 7;
                                }

                                this.owner = client;
                            }
                        } else {
                            errorCode = 8;
                        }
                }

                // 
                this.printTransition(client, "download", mode, oldState, this.state, errorCode);
                if (errorCode > 0) {
                    return null;
                } else {
                    FileContents contents = new FileContents(this.bytes);
                    if (oldState == 3) {
                        synchronized(this.inStateBack2WriteShared) {
                            this.inStateBack2WriteShared.notifyAll();
                            System.out.println(client + " woke up all waiting on inStateBack2WriteShared");
                        }
                    }

                    return contents;
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
            int errorCode = this.state != 0 && this.state != 1 ? 0 : 2;

            int oldState;
            for(oldState = 0; errorCode == 0 && oldState < this.readers.size(); ++oldState) {
                String reader = (String)this.readers.elementAt(oldState);
                ClientInterface clientInterface = null;

                try {
                    clientInterface = (ClientInterface)Naming.lookup("rmi://" + reader + ":" + this.port + "/fileclient");
                } catch (Exception e) {
                    e.printStackTrace();
                    errorCode = 3;
                }

                if (clientInterface != null) {
                    try {
                        clientInterface.invalidate();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }

                    System.out.println("update( " + this.name + " ): " + reader + "'s copy was invalidated");
                }
            }

            this.readers.removeAllElements();
            oldState = this.state;
            if (errorCode == 0) {
                this.bytes = contents.get();
                System.out.println("bytes = " + new String(this.bytes));
                switch(this.state) {
                    case 2:
                        this.state = 0;
                        this.owner = null;
                        errorCode = !this.fileWrite() ? 4 : 0;
                        break;
                    case 3:
                        this.state = 2;
                        this.owner = client;
                        this.notify();
                }
            }

            this.printTransition(client, "upload", "w", oldState, this.state, errorCode);
            return errorCode == 0;
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
