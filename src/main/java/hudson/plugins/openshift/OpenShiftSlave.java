package hudson.plugins.openshift;

import hudson.Extension;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.model.Descriptor.FormException;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.NodeProperty;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

import com.openshift.client.IApplication;
import com.openshift.client.IDomain;
import com.openshift.client.IGearProfile;
import com.openshift.client.IOpenShiftConnection;
import com.openshift.client.IUser;
import com.openshift.client.OpenShiftException;
import com.openshift.client.cartridge.IStandaloneCartridge;
import com.openshift.client.configuration.DefaultConfiguration;
import com.openshift.client.configuration.SystemConfiguration;
import com.openshift.client.configuration.UserConfiguration;

public class OpenShiftSlave extends AbstractCloudSlave {
    private static final long serialVersionUID = 8486485671018263774L;
    private static final Logger LOGGER = Logger.getLogger(OpenShiftSlave.class
            .getName());

    private String framework;
    private final String builderSize;
    private final long builderTimeout;
    private String uuid;

    /**
     * The name of the slave should be the 'sanitized version of the framework
     * that is used, removing all '.' and '-' characters (i.e. jbossas70, php53)
     * <p/>
     * The framework should be the exact OpenShift framework used (i.e.
     * jbossas-7)
     */
    @DataBoundConstructor
    public OpenShiftSlave(String name, String framework, String builderSize,
                          String label, long builderTimeout, int executors, int slaveIdleTimeToLive) throws FormException, IOException {
        super(name, "Builder for " + label, "app-root/data/jenkins", executors, Mode.NORMAL,
                label, new OpenShiftComputerLauncher(),
                new CloudRetentionStrategy(slaveIdleTimeToLive), Collections
                .<NodeProperty<?>>emptyList());

        LOGGER.info("Creating slave with " + slaveIdleTimeToLive + "mins time-to-live");

        this.framework = framework;
        this.builderSize = builderSize;
        this.builderTimeout = builderTimeout;
    }

    @SuppressWarnings("unchecked")
    @Override
    public AbstractCloudComputer<OpenShiftSlave> createComputer() {
        return new OpenShiftComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException,
            InterruptedException {
        LOGGER.info("Terminating slave " + name + " (uuid: " + uuid + ")");

        if (getComputer() != null && getComputer().getChannel() != null) {
            LOGGER.info("Closing the SSH channel...");
            getComputer().getChannel().close();
        }

        LOGGER.info("Terminating OpenShift application...");
        terminateApp();
    }

    protected IStandaloneCartridge getCartridge(IOpenShiftConnection connection) throws OpenShiftException {
      String targetCartridgeName = framework.replace("redhat-", "");

      List<IStandaloneCartridge> cartridges = connection.getStandaloneCartridges();
      for (IStandaloneCartridge cartridge : cartridges) {
        if (cartridge.getName().equals(targetCartridgeName)) {
          return cartridge;
        }
      }

      throw new OpenShiftException("Cartridge for " + targetCartridgeName + " not found");
    }

    private void terminateApp() {

        try {
            IOpenShiftConnection connection = OpenShiftCloud.get().getOpenShiftConnection();
            IUser user = connection.getUser();

            user.getDefaultDomain().getApplicationByName(name).destroy();
        } catch (Exception e) {
            LOGGER.warning("Unable to terminate application");
            e.printStackTrace();
        }
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {
        public String getDisplayName() {
            return "OpenShift Slave";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    public String getHostName() throws IOException {
        try {
            IUser user = OpenShiftCloud.get().getOpenShiftConnection().getUser();
            IApplication app = user.getDefaultDomain().getApplicationByName(name);
            String url = app.getApplicationUrl();

            if (url.indexOf("//") != -1)
                url = url.substring(url.indexOf("//") + 2);

            url = url.replace("/", "");

            return url;
        } catch (OpenShiftException e) {
            throw new IOException("Unable to find application url for " + name, e);
        }
    }

    public void connect(boolean delayDNS) throws IOException {
        LOGGER.info("Connecting to slave " + name + "...");

        try {
            // Force a refresh of the user info to get the application UUID
            IUser user = OpenShiftCloud.get().getOpenShiftConnection().getUser();
            IApplication app = user.getDefaultDomain().getApplicationByName(name);

            if (app == null)
                throw new IOException("Failed to connect/find application " + name);

            uuid = app.getGearGroups().iterator().next().getGears().iterator().next().getId();

            LOGGER.info("Established UUID = " + uuid);
        } catch (OpenShiftException e) {
            throw new IOException("Unable to connect to application " + name, e);
        }

        // Sleep for 5 seconds for DNS to propagate to minimize cache penalties
        if (delayDNS) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        long startTime = System.currentTimeMillis();
        long currentTime = startTime;
        // Wait until DNS is resolvable
        while (isBuildRunning() && (builderTimeout == -1 || currentTime - startTime < builderTimeout)) {
            try {
                String hostname = getHostName();
                LOGGER.info("Checking to see if slave DNS for " + hostname + " is resolvable ... (timeout: " + builderTimeout + "ms)");
                InetAddress address = InetAddress.getByName(hostname);
                LOGGER.info("Slave DNS resolved - " + address);
                break;
            } catch (UnknownHostException e) {
                LOGGER.info("Slave DNS not propagated yet, retrying... (remaining: " + (builderTimeout - (currentTime - startTime)) + "ms)");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    // Ignore interruptions
                }
                currentTime = System.currentTimeMillis();
            }
        }

        if (builderTimeout >= 0 && currentTime - startTime >= builderTimeout) {
            LOGGER.warning("Slave DNS not propagated. Timing out.");
            throw new IOException("Slave DNS not propagated. Timing out.");
        }
    }

    protected boolean isBuildRunning() {
        boolean running = true;
        Queue queue = Hudson.getInstance().getQueue();
        if (queue != null) {
            Queue.Item[] items = queue.getItems();
            if (items.length == 0)
                running = false;
        }

        return running;

    }

    public void provision() throws Exception {

        // Create a new application of the right type
        createApp();

        // Force a connection to establish the UUID
        connect(true);
    }

    private void createApp() throws IOException, OpenShiftException {
      IOpenShiftConnection connection = OpenShiftCloud.get().getOpenShiftConnection();
      IUser user = connection.getUser();
      IStandaloneCartridge cartridge = getCartridge(OpenShiftCloud.get().getOpenShiftConnection());

      IDomain domain = user.getDefaultDomain();
      List<IGearProfile> gearProfiles = domain.getAvailableGearProfiles();
      IGearProfile gearProfile = gearProfiles.get(0);
      for (IGearProfile profile : gearProfiles) {
        if (profile.getName().equals(builderSize)) {
          gearProfile = profile;
        }
      }

      LOGGER.info("Creating builder application " + cartridge.getName() + " " + name + " " + user.getDefaultDomain().getId() + " of size " + gearProfile.getName() + " ...");

      IApplication app = domain.createApplication(name, cartridge, gearProfile);

      // No reason to have app running on builder gear - just need it installed
      LOGGER.info("Stopping application on builder gear ...");
      app.stop();
    }

    public String getUuid() {
        return uuid;
    }
}
