package com.axway.jbt.st.sharemagic;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tumbleweed.st.server.api.AccountManager;
import com.tumbleweed.st.server.api.ApplicationManager;
import com.tumbleweed.st.server.api.Events;
import com.tumbleweed.st.server.api.Factory;
import com.tumbleweed.st.server.api.NoSuchApplicationException;
import com.tumbleweed.st.server.api.Subscription;
import com.tumbleweed.st.server.api.SubscriptionCriterion;
import com.valicert.brules.executionengine.BaseAgent;

/**
 * This agent is intended for use on the login event.
 * 
 * It synchronizes the symlinks to folders found in an account with the
 * account's subscriptions to specially notated applications.  Any application
 * with a line of the form:
 *    share=/path/to/share
 * in the Description field is considered specially notated.  This agent will
 * replace the default private subscription folder with a symlink to the
 * /path/to/share from the Description.
 * 
 * The agent assumes that all symlinks in the account are or were present as
 * a result of this mechanism, so any unmatched symlinks are removed.  This
 * allows subscriptions and application notations to be changed at any time,
 * and subscribing accounts will be cleaned up or reconfigured at the next login.
 *
 */
public class SymlinkAgent extends BaseAgent {

    final static Logger eventlog = LoggerFactory.getLogger("events-"+SymlinkAgent.class.getName());
    
    /**
     * A simple "struct" style class to keep track of the
     * link/target Path pairs returned by getSharedSubscriptions.
     */
    private class SharedSubscription {
        public Path link;
        public Path target;
        SharedSubscription (String link, String target) {
            this.target = FileSystems.getDefault().getPath(target);
            this.link   = FileSystems.getDefault().getPath(
                              getEnvironmentVariable(Events.DXAGENT_HOMEDIR),
                              link);
        }
    }
    
    private static final Pattern NOTES = Pattern.compile("(?:.*\\r\\n)?\\s*share\\s*=\\s*([^\\r\\n]*)(?:\\r\\n.*)?",
                                                         Pattern.MULTILINE|Pattern.DOTALL); 

    /**
     * Walks through all the current account's subscriptions looking for
     * Applications with a "share=/path/to/share" note in the Description.
     * @return a Collection of SharedSubscriptions, possibly empty
     */
    private Collection<SharedSubscription> getSharedSubscriptions() {
        ArrayList<SharedSubscription> result = new ArrayList<SharedSubscription>();
        AccountManager am = Factory.getInstance().getAccountManager();
        ApplicationManager lm = Factory.getInstance().getApplicationManager();
        SubscriptionCriterion me = lm.getSubscriptionCriterion().account(am.newAccountId(getEnvironmentVariable(Events.DXAGENT_ACCOUNT_ID)));
        for (Subscription sub : lm.getSubscriptions(me)) {
            String folder = sub.getFolderPath();
            String notes;
            try {
                notes = lm.getApplication(sub.getApplicationId()).getNotes();
            } catch (NoSuchApplicationException e) {
                // just log and ignore it -- shouldn't happen
                eventlog.debug("getApplication for "+folder+" failed");
                notes = "";
            }
            Matcher m = NOTES.matcher(notes);
            if (m.matches()) {
                String target = m.group(1);
                if (Files.exists(FileSystems.getDefault().getPath(target))) {
                    result.add(new SharedSubscription(folder, target));
                    eventlog.debug("subscription at "+folder+" shared at "+target);
                } else {
                    eventlog.debug("subscription at "+folder+" shared at "+target+", which doesn't exist -- ignored");
                }
            }
        }
        return result;
    }
    
    /**
     * FileVisitor implementation that looks for symlink directories
     * and adds them to a Path Collection.
     */
    private class SymlinkFinder extends SimpleFileVisitor<Path> {
        private Set<Path> result;
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
        throws IOException
        {
            if (attrs.isSymbolicLink() && Files.isDirectory(file)) {
                result.add(file);
                eventlog.debug("found shared folder "+file);
            }
            return FileVisitResult.CONTINUE;
        }
        public SymlinkFinder(Set<Path> result) {
            this.result = result;
        }
    }
    
    /**
     * Walks the current account home directory looking for
     * symlinked directories.
     * @param home the current account home directory
     * @return the symlinked directories found as a Collection of Paths
     */
    private Set<Path> getSharedFolders(Path home) {
        HashSet<Path> result = new HashSet<Path>();
        try {
            SymlinkFinder walker = new SymlinkFinder(result);
            Files.walkFileTree(home, walker);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return result;
    }

    /**
     * Compares a list of shared subscriptions to a set of shared folders, removing
     * reconciled entries from both collections.  In order to be considered reconciled,
     * both the subscription folder must be a symlink pointing to the intended shared
     * folder target.
     * @param folders the Set of shared folders found in the current account
     * @param shares the Collection of shared folder subscriptions found for the current account
     */
    private void reconcileLinks(Set<Path> folders,
            Collection<SharedSubscription> shares) {
        for (Iterator<SharedSubscription> i = shares.iterator(); i.hasNext();) {
            SharedSubscription sub = i.next();
            if (folders.contains(sub.link)) {
                try {
                    Path target = sub.link.toRealPath();
                    if (target.compareTo(sub.target) == 0) { 
                        // everything matches up -- remove from the collections
                        folders.remove(sub.link);
                        i.remove();
                        eventlog.debug("shared folder is correct: "+sub.link+" -> "+sub.target);
                    } else {
                        // leave things alone: the folders entry will cause the existing share to be removed,
                        // and the shares entry will cause a new symlink to be created.
                        eventlog.debug("shared folder is incorrect: "+sub.link+" -> "+target+" should be "+sub.target);
                    }
                } catch (IOException e) {
                    // Bad symlink, so treat it like a mismatch and we'll try to remove and recreate it.
                    eventlog.debug("error resolving symlink: "+e);
                }
            } else {
                // looks like a newly found subscription, so let it get linked in the next phase
                eventlog.debug("found new shared folder: "+sub.link+" -> "+sub.target);
            }
        }
    }

    /**
     * Purges recursively a folder.
     * @param folder the folder to purge
     * @return true if the folder was purged
     */
    private boolean purgeFolder(Path folder) {
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException
                {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e)
                    throws IOException
                {
                    if (e == null) {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    } else {
                        // directory iteration failed
                        throw e;
                    }
                }
            });
        } catch (IOException e) {
            eventlog.error("could not purge \""+folder+"\": "+e);
            return false;
        }
        eventlog.debug("purged folder \""+folder+"\"");
        return true;
    }

    /**
     * Deletes an empty folder, including a folder with just .stfs in it
     * which is considered to be virtually empty.
     * @param folder the folder to delete
     * @return true if the folder was deleted
     */
    private boolean deleteEmptyFolder(Path folder) {
        if (Files.isDirectory(folder)) {
            try {
                Files.delete(folder);
                eventlog.debug("deleted empty folder \""+folder+"\"");
                return true;
            } catch (DirectoryNotEmptyException e) {
                // maybe it is just the .stfs directory
                String[] files = folder.toFile().list();
                if (files.length==1 && files[0].equals(".stfs")) {
                    return purgeFolder(folder);
                }
            } catch (IOException e) {
                // some other problem, but there isn't much to do in this case
                eventlog.error("error deleting folder \""+folder+"\": "+e);
            }
        }
        return false;
    }

    /**
     * Removes all the symlinks in the Set, including removing any empty
     * enclosing folders up-to-but-not-including the account home directory
     * itself.
     * @param folders the Set of symlinks to folders to remove
     * @param home the account home directory
     */
    private void removeBadLinks(Set<Path> folders, Path home) {
        for (Path folder : folders) {
            try {
                Files.delete(folder);
                eventlog.debug("deleted symlink \""+folder+"\"");
            } catch (IOException e) {
                eventlog.error("could not delete incorrect symlink\""+folder+"\": "+e);
            }
            folder = folder.getParent();
            while (folder != null && folder.compareTo(home) != 0 && deleteEmptyFolder(folder)) {
                folder = folder.getParent();
            }
        }
    }

    /**
     * Renames a file by adding a .number suffix, starting with .1
     * and incrementing until an available name is found.
     * @param path the file to rename
     */
    private void renameUnique(Path path) {
        String rename = path.getFileName().toString();
        int    i      = 1;
        while (Files.exists(path.resolveSibling(rename+"."+i), LinkOption.NOFOLLOW_LINKS)) {
            i++;
        }
        try {
            Files.move(path, path.resolveSibling(rename+"."+i));
            eventlog.error("renamed existing folder to "+path+"."+i);
        } catch (IOException e) {
            eventlog.error("unable to rename existing folder "+path);
        }
    }

    /**
     * Establishes the shared directory links indicated, either deleting
     * the existing link folder if it is empty, or safely renaming it otherwise
     * (i.e. it is not a folder or it is not empty).
     * @param shares the list of links to create
     */
    private void addNewLinks(Collection<SharedSubscription> shares) {
        for (SharedSubscription sub : shares) {
            if (Files.exists(sub.link, LinkOption.NOFOLLOW_LINKS)) {
                if (!deleteEmptyFolder(sub.link)) { 
                    renameUnique(sub.link);
                }
            }
            try {
                Files.createSymbolicLink(sub.link, sub.target);
                eventlog.debug("linked "+sub.link+" -> "+sub.target);
            } catch (IOException e) {
                eventlog.error("unable to link "+sub.link+" -> "+sub.target+": "+e);
            }
        }
    }

    /* (non-Javadoc)
     * @see com.valicert.brules.executionengine.BaseAgent#executeAgent()
     */
    @Override
    protected boolean executeAgent() {
        // survey the current account's shared folder and "shared" subscriptions
        Path home = FileSystems.getDefault().getPath(getEnvironmentVariable(Events.DXAGENT_HOMEDIR));
        Set<Path> folders = getSharedFolders(home);
        Collection<SharedSubscription> shares = getSharedSubscriptions();
        // eliminate matching folders and shares
        reconcileLinks(folders, shares);
        // clean out stuff that needs to go
        removeBadLinks(folders, home);
        // set up new shares
        addNewLinks(shares);
        return super.executeAgent();
    }
}
