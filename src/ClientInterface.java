import java.rmi.Remote;
import java.rmi.RemoteException;

public interface ClientInterface extends Remote
{
    public boolean invalidate() throws RemoteException;

    public boolean writeback() throws RemoteException;
}