package ggp.apollo;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.jdo.PersistenceManager;
import javax.jdo.annotations.*;

@PersistenceCapable
public class ServerState {
    @SuppressWarnings("unused")
    @PrimaryKey @Persistent private String thePrimaryKey;
    @Persistent private Set<String> theRunningMatches;
    @Persistent private String theBackendAddress;
    @Persistent private Integer theBackendErrors;
    @Persistent private Integer theSchedulingRound;    
    
    private ServerState() {
        thePrimaryKey = "ServerState";
        theRunningMatches = new HashSet<String>();
        theBackendAddress = "";
        theBackendErrors = 0;
        theSchedulingRound = 0;    
    }
    
    public Set<String> getRunningMatches() {
        return theRunningMatches;
    }
    
    public void setBackendAddress(String theBackendAddress) {
        this.theBackendAddress = theBackendAddress;
    }

    public String getBackendAddress() {
        return theBackendAddress;
    }

    public void incrementSchedulingRound() {
        this.theSchedulingRound++;
    }

    public int getSchedulingRound() {
        return theSchedulingRound;
    }
    
    public void incrementBackendErrors() {
        this.theBackendErrors++;
    }
    
    public void clearBackendErrors() {
        this.theBackendErrors = 0;
    }    

    public Integer getBackendErrors() {
        return theBackendErrors;
    }
    
    public void save() {
        PersistenceManager pm = Persistence.getPersistenceManager();
        pm.makePersistent(this);
        pm.close();        
    }
    
    /* Static accessor methods */
    public static ServerState loadState() throws IOException {
        Set<ServerState> theStates = Persistence.loadAll(ServerState.class);
        if (theStates.size() > 0) {
            return theStates.iterator().next();
        } else {
            return new ServerState();
        }
   }
}