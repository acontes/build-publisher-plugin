/**
 * Hudson instance description.
 */
package hudson.plugins.build_publisher;

import hudson.XmlFile;
import hudson.maven.AbstractMavenProject;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Build;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Project;
import hudson.model.Run;
import hudson.model.TopLevelItem;
import hudson.model.listeners.ItemListener;
import hudson.plugins.build_publisher.BuildPublisher.BuildPublisherDescriptor;
import hudson.plugins.build_publisher.StatusInfo.State;
import hudson.tasks.BuildStep;
import hudson.tasks.LogRotator;
import hudson.tasks.Mailer;
import hudson.tasks.Publisher;
import hudson.tasks.junit.JUnitResultArchiver;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.FileRequestEntity;
import org.apache.commons.httpclient.methods.GetMethod;

/**
 * Represents remote public Hudson instance.
 *
 * @author dvrzalik@redhat.com
 *
 */
public final class HudsonInstance {

    static final Logger LOGGER = Logger.getLogger(Hudson.class.getName());

    private String url;
    private String name;
    private String login;
    private String password;

    // Builds to be published
    private transient LinkedHashSet<AbstractBuild> publishRequestQueue = new LinkedHashSet<AbstractBuild>();

    private transient PublisherThread publisherThread;
    transient BuildTransmitter buildTransmitter;
    private transient HttpClient client;

    public String getLogin() {
        return login;
    }

    public String getPassword() {
        return password;
    }

    public HudsonInstance(String name, String url, String login, String password) {
        this.name = name;
        this.url = url;
        this.login = login;
        this.password = password;

        initVariables();
        restoreQueue();
        initPublisherThread();

    }

    public String getUrl() {

        if (url != null && !url.endsWith("/")) {
            url += '/';
        }

        return url;
    }

    public String getName() {
        return name;
    }

    /**
     * Append the build to the publishing queue.
     */
    public void publishNewBuild(AbstractBuild build) {
        publishBuild(build, new StatusInfo(State.PENDING, "Waiting in queue",
                name, null));

    }

    /**
     * Same as previous, but doesn't set status for the build.
     */
    public synchronized void publishBuild(AbstractBuild build, StatusInfo status) {
        publishRequestQueue.add(build);
        StatusAction.setBuildStatusAction(build, status);
        saveQueue();
        notifyAll();
    }

    public void abortTransmission(AbstractBuild request) {
        publisherThread.abortTrasmission(request);
    }

    // XStream init
    private Object readResolve() {
        initVariables();

        // let's wait until Hudson's initialized
        Hudson.getInstance().getJobListeners().add(new ItemListener() {
            @Override
            public void onLoaded() {
                restoreQueue();
                initPublisherThread();
            }
        });

        return this;
    }

    private void initVariables() {
        publishRequestQueue = new LinkedHashSet<AbstractBuild>();
        buildTransmitter = new HTTPBuildTransmitter();
        MultiThreadedHttpConnectionManager connectionManager = new MultiThreadedHttpConnectionManager();
        client = new HttpClient(connectionManager);
        // --- authentication
        setCredentialsFroClient(client);
    }

    private void initPublisherThread() {
        if(publisherThread == null) {
            publisherThread = new PublisherThread(HudsonInstance.this);
            publisherThread.start();
        }
    }

    void setCredentialsFroClient(HttpClient httpClient) {
        try {
            URL sunUrl = new URL(url);
            if (login != null) {
                Credentials credentials = new UsernamePasswordCredentials(
                        login, password);
                httpClient.getState().setCredentials(
                        new AuthScope(sunUrl.getHost(), AuthScope.ANY_PORT,
                                AuthScope.ANY_REALM, AuthScope.ANY_SCHEME),
                        credentials);
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
            LOGGER.severe(e.getMessage());
        }
    }

    HttpClient getHttpClient() {
        return client;
    }

    synchronized void removeRequest(AbstractBuild request, StatusInfo statusInfo) {
        if (publishRequestQueue.contains(request)) {
            publishRequestQueue.remove(request);
            saveQueue();
            StatusAction.setBuildStatusAction(request, statusInfo);
        }
    }

    synchronized AbstractBuild nextRequest() {
        // If there is nothing to do let's wait until next
        // PublishRequest
        waitForRequest();
        return publishRequestQueue.iterator().next();

    }

    private void waitForRequest() {
        while (publishRequestQueue.isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
                return;
            }
        }
    }

    private void saveQueue() {
        List<RequestHolder> holders = new LinkedList<RequestHolder>();
        for (AbstractBuild request : publishRequestQueue) {
            RequestHolder holder = new RequestHolder(request.getNumber(),
                    request.getProject().getFullName());
            holders.add(holder);
        }
        XmlFile file = new XmlFile(new File(Hudson.getInstance().getRootDir(),
                "bp-" + name + ".xml"));
        try {
            file.write(holders);
        } catch (IOException e) {
            e.printStackTrace();
            LOGGER.severe(e.getMessage());
        }
    }

    private void restoreQueue() {
        XmlFile file = new XmlFile(new File(Hudson.getInstance().getRootDir(),
                "bp-" + name + ".xml"));
        try {
            List<RequestHolder> holders = (List<RequestHolder>) file.read();
            for (RequestHolder holder : holders) {
                String projectName = holder.project;
                Item project = Hudson.getInstance().getItemByFullName(
                        projectName);
                if (project instanceof AbstractProject) {
                    Run build = ((AbstractProject) project)
                            .getBuildByNumber(holder.build);
                    if (build instanceof AbstractBuild) {
                        publishRequestQueue.add((AbstractBuild) build);
                    }
                }
            }

        } catch (IOException e) {
            LOGGER.severe("Could not restore publisher queue from "
                    + file.getFile().getAbsolutePath());
        }
    }

    private static class RequestHolder {
        int build;
        String project;

        public RequestHolder(int build, String project) {
            this.build = build;
            this.project = project;
        }
    }

}