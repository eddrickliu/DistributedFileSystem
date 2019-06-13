import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Vector;

/**
 * This file server class is meant to act as the central server for the many clients
 * that will be connected to this program. This server classes will receive all the
 * commands from the clients utilizing the java RMI.
 */
public class FileServer extends UnicastRemoteObject implements ServerInterface {
    //instance variable for port as well as the cache
    private String port = null;
    private Vector<FileServer.File> cache = null;

    /**
     * Argument contsructor to set the instance variable of the File Server
     * cache will be sent to an empty Vector
     *
     * @param port      The string representing the port number
     * @throws RemoteException  is needed for RMI
     */
    public FileServer(String port) throws RemoteException {
        this.port = port;
        this.cache = new Vector();
    }

    /**
     * Main method to take in arguments passed in through the terminal
     * and create a serer object to be used. As well as binding the Server
     * to the rmi regesitry
     *
     * @param args String array that contains all the arugments of the
     *             File Server
     *             Arg0: port Number that will be shared of the clients and server
     *
     */
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

    /**
     * Method to download a file from a client, adds the current file into the
     * cache while checking through the whole cache
     *
     * @param client    String that represents the client that is the client that
     *                  is requesting the download function
     * @param name      String that is the file name of the file that is wanted to
     *                  be downloaded
     * @param mode      String that is the mode of the file that is wanted to be
     *                  downloaded
     * @return          returns the file contents of the file that is being downloaded
     */
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
     * Method to upload a file to a file client
     *
     * @param client
     * @param fileName
     * @param contents
     * @return true if the file is uploaded
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

    /**
     * Private inner class to help with describing a File as well as
     * as the various states that it can be in. The following 4 states
     * are used: notShared, readShared, writeShared, back2WriteShared
     */
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

        /**
         * Method to remove a reader from within the list of reader.
         *
         *
         * @param readerToRemove
         * @return true if the reader is removed
         */
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

        /**
         *
         * Method to printout all the readers from the list of readiers
         *
         *
         */
        private void listReaders() {
            System.out.println("Number of readers: " + this.readers.size());

            for(int i = 0; i < this.readers.size(); ++i) {
                String reader = (String)this.readers.elementAt(i);
                System.out.println("\tReader: " + reader);
            }

        }

        /**
         *
         * Method to print the transition of state for a file.
         * Prints all relevant information on the File server
         * side of the program
         *
         * @param client    the client that this information is being put on
         * @param downloadOrUpload      String that describes if the file is being uploaded or
         *                              downloaded
         * @param mode                  String that describes the mode of the file that
         * @param oldState              The old state that the file was in
         * @param newState              the new state that the file will become
         * @param errorCode             error code to be printed
         */
        private void printTransition(String client, String downloadOrUpload, String mode, int oldState, int newState, int errorCode) {
            System.out.println(this.name + " requested by " + client + " for " + downloadOrUpload + "(" + mode + "). State( " + 
            		(oldState == 0 ? "notshared" : (oldState == 1 ? "readshared" : (oldState == 2 ? "writeshared" : 
            		"back2writeshared"))) + " --> " + (newState == 0 ? "notshared" : (newState == 1 ? "readshared" : 
            		(newState == 2 ? "writeshared" : "back2writeshared"))) + " ). Error code: " + errorCode);
            this.listReaders();
            System.out.println("Owner: " + this.owner);
        }

        /**
         * Argument that takes in 2 variables and sets all the instance variables
         * of this File object.
         *
         * @param name
         * @param port
         */
        public File(String name, String port) {
            this.name = name;
            this.readers = new Vector();
            this.owner = null;
            this.port = port;
            this.inStateBack2WriteShared = new Object();
            this.bytes = this.fileRead();
        }

        /**
         * Method that reads the file
         *
         * @return the bytes of the file that is read.
         */
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

        /**
         * Method to write to a file to the output stream
         *
         *
         * @return  true if the file is written
         */
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
         * Method to download a file to client
         *
         * @param client       String that is the client that is being download
         * @param mode         String that is the mode of the file
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
         * Method to download a file's contnts into a file within the client.
         *
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

        /**
         * Getter method to if the state is matching the previously
         * defined notShared State.
         *
         * @return true if matches notShared state
         */
        public synchronized boolean isStateNotShared() {
            return this.state == 0;
        }

        /**
         /**
         * Getter method to if the state is matching the previously
         * defined readshared State.
         *
         * @return true if matches readshared state
         */
        public synchronized boolean isStateReadShared() {
            return this.state == state_readshared;
        }

        /**
         * Getter method to if the state is matching the previously
         * defined isStateWriteShared State.
         *
         * @return true if matches isStatewriteshared state
         */
        public synchronized boolean isStateWriteShared() {
            return this.state == state_writeshared;
        }

        /**
         * Getter method to if the state is matching the previously
         * defined back2Writeshared State.
         *
         * @return true if matches back2writeshared state
         */
        public synchronized boolean isStatebackToWriteShared() {
            return this.state == state_back2writeshared;
        }

        /**
         * Method to ask the question does this file's
         * name match the passed filename.
         *
         * @param fileName String that is the file name that is
         *                 desired to be checked
         * @return true if the name is right, false if it is not
         */
        public synchronized boolean hit(String fileName) {
            return this.name.equals(fileName);
        }
    }
}
