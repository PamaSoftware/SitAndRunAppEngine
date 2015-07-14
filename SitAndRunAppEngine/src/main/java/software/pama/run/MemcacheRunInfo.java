package software.pama.run;

import java.io.Serializable;

/**
 * Created by Pawel on 2015-07-12.
 */
public class MemcacheRunInfo implements Serializable {
    boolean isRunWithRandom = false;
    String hostLogin = null;

    public MemcacheRunInfo() {

    }

    public MemcacheRunInfo(boolean isRunWithRandom) {
        this.isRunWithRandom = isRunWithRandom;
    }

    public MemcacheRunInfo(boolean isRunWithRandom, String hostLogin) {
        this.isRunWithRandom = isRunWithRandom;
        this.hostLogin = hostLogin;
    }

    public void setHostLogin(String hostLogin) {
        this.hostLogin = hostLogin;
    }

    public void setRunWithRandom(boolean isRunWithRandom) {
        this.isRunWithRandom = isRunWithRandom;
    }

    public String getHostLogin() {
        return hostLogin;
    }

    public boolean isRunWithRandom() {
        return isRunWithRandom;
    }
}
