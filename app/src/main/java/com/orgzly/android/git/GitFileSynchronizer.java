package com.orgzly.android.git;

import static com.orgzly.android.ui.AppSnackbarUtils.showSnackbar;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.orgzly.R;
import com.orgzly.android.App;
import com.orgzly.android.util.MiscUtils;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public class GitFileSynchronizer {
    private final static String TAG = GitFileSynchronizer.class.getSimpleName();

    private final Git git;
    private final GitPreferences preferences;
    private final Context context;
    private final Activity currentActivity = App.getCurrentActivity();


    public GitFileSynchronizer(Git g, GitPreferences prefs) {
        git = g;
        preferences = prefs;
        context = App.getAppContext();
    }

    private GitTransportSetter transportSetter() {
        return preferences.createTransportSetter();
    }

    public void retrieveLatestVersionOfFile(
            String repositoryPath, File destination) throws IOException {
        MiscUtils.copyFile(repoDirectoryFile(repositoryPath), destination);
    }

    private void fetch() throws IOException {
        try {
            transportSetter()
                    .setTransport(git.fetch()
                            .setRemote(preferences.remoteName())
                            .setRemoveDeletedRefs(true))
                    .call();
        } catch (GitAPIException e) {
            e.printStackTrace();
            throw new IOException(e.getMessage());
        }
    }

    public void checkoutSelected() throws GitAPIException {
        git.checkout().setName(preferences.branchName()).call();
    }

    public boolean mergeWithRemote() throws IOException {
        ensureRepoIsClean();
        try {
            fetch();
            RevCommit mergeTarget = getCommit(
                    String.format("%s/%s", preferences.remoteName(),
                            git.getRepository().getBranch()));
            return doMerge(mergeTarget);
        } catch (GitAPIException e) {
            e.printStackTrace();
        }
        return false;
    }

    private String getShortHash(ObjectId hash) {
        String shortHash = hash.getName();
        try {
            shortHash = git.getRepository().newObjectReader().abbreviate(hash).name();
        } catch(IOException e) {
            Log.e(TAG, "Error while abbreviating commit hash " + hash.getName() + ", falling back to full hash");
        }
        return shortHash;
    }

    private String createMergeBranchName(String repositoryPath, ObjectId commitHash) {
        String shortCommitHash = getShortHash(commitHash);
        repositoryPath = repositoryPath.replace(" ", "_");
        String now = new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime());
        return "merge-" + repositoryPath + "-" + shortCommitHash + "-" + now;
    }

    public boolean updateAndCommitFileFromRevisionAndMerge(
            File sourceFile, String repositoryPath,
            ObjectId fileRevision, RevCommit revision)
            throws IOException {
        ensureRepoIsClean();
        if (updateAndCommitFileFromRevision(sourceFile, repositoryPath, fileRevision)) {
            return true;
        }

        String originalBranch = git.getRepository().getFullBranch();
        String mergeBranch = createMergeBranchName(repositoryPath, fileRevision);
        try {
            git.branchDelete().setBranchNames(mergeBranch).call();
        } catch (GitAPIException e) {}
        boolean mergeSucceeded = true;
        boolean doCleanup = false;
        try {
            RevCommit mergeTarget = currentHead();
            // Try to use the branch "orgzly-pre-sync-marker" to find a good point for branching off.
            RevCommit branchStartPoint = getCommit("orgzly-pre-sync-marker");
            if (branchStartPoint == null) {
                branchStartPoint = revision;
            }
            git.checkout().setCreateBranch(true).setForceRefUpdate(true).
                    setStartPoint(branchStartPoint).setName(mergeBranch).call();
            if (!currentHead().equals(branchStartPoint))
                throw new IOException("Failed to create new branch at " + branchStartPoint.toString());
            if (!updateAndCommitFileFromRevision(sourceFile, repositoryPath, fileRevision))
                throw new IOException(
                        String.format(
                                "The provided file revision %s for %s is " +
                                        "not the same as the one found in the provided commit %s.",
                                fileRevision.toString(), repositoryPath, revision.toString()));
            mergeSucceeded = doMerge(mergeTarget);
            if (mergeSucceeded) {
                RevCommit merged = currentHead();
                git.checkout().setName(originalBranch).call();
                MergeResult result = git.merge().include(merged).call();
                if (!result.getMergeStatus().isSuccessful()) {
                    throw new IOException("Unexpected failure to merge branch");
                }
            }
        } catch (GitAPIException e) {
            doCleanup = true;
            e.printStackTrace();
            throw new IOException(
                    String.format("Failed to handle merge correctly: %s", e.getMessage()));
            // TODO: want to catch CheckoutConflictException as well, that means that the actual merge produced conflicts
        } finally {
            if (mergeSucceeded || doCleanup) try {
                git.checkout().setName(originalBranch).call();
                git.branchDelete().setBranchNames(mergeBranch);
            } catch (GitAPIException e) {
            }
        }
        return mergeSucceeded;
    }

    private boolean doMerge(RevCommit mergeTarget) throws IOException, GitAPIException {
        MergeResult result = git.merge().include(mergeTarget).call();
        if (result.getMergeStatus().equals(MergeResult.MergeStatus.CONFLICTING)) {
            gitResetMerge();
            return false;
        }
        return true;
    }

    /**
     * Try to push to remote if local and remote HEADs for the current branch
     * point to different commits. This method was added to allow pushing only
     * once per sync occasion: right after the "for namesake in namesakes"-loop
     * in SyncService.doInBackground().
     */
    public void tryPushIfHeadDiffersFromRemote() {
        String branchName = null;
        String remoteName = null;
        RevCommit localHead = null;
        RevCommit remoteHead = null;
        Repository repo = git.getRepository();

        try {
            branchName = repo.getBranch();
            localHead = currentHead();
            remoteName = preferences.remoteName();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // If the current branch exists on the remote side, find out its HEAD commit.
        try {
            List<Ref> remoteBranches = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();
            for (Ref remoteBranch : remoteBranches) {
                if (remoteBranch.getName().equals("refs/remotes/" + remoteName + "/" + branchName)) {
                    remoteHead = getCommit(remoteName + "/" + branchName);
                    break;
                }
            }
        } catch (GitAPIException | IOException e) {
            e.printStackTrace();
        }

        if (localHead != null && !localHead.equals(remoteHead)) {
            tryPush();
        }
    }

    public void tryPush() {
        final var pushCommand = transportSetter().setTransport(
                git.push().setRemote(preferences.remoteName()));
        final Object monitor = new Object();

        App.EXECUTORS.diskIO().execute(() -> {
            try {
                Iterable<PushResult> results = (Iterable<PushResult>) pushCommand.call();
                // org.eclipse.jgit.api.PushCommand swallows some errors without throwing exceptions.
                if (!results.iterator().next().getMessages().isEmpty()) {
                    if (currentActivity != null) {
                        showSnackbar(currentActivity, results.iterator().next().getMessages());
                    }
                }
                synchronized (monitor) {
                    monitor.notify();
                }
            } catch (GitAPIException e) {
                if (currentActivity != null) {
                    showSnackbar(
                            currentActivity,
                            String.format("Failed to push to remote: %s", e.getMessage())
                    );
                }
            }
        });
        synchronized (monitor) {
            try {
                monitor.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void gitResetMerge() throws IOException, GitAPIException {
        git.getRepository().writeMergeCommitMsg(null);
        git.getRepository().writeMergeHeads(null);
        git.reset().setMode(ResetCommand.ResetType.HARD).call();
    }

    public boolean updateAndCommitFileFromRevision(
            File sourceFile, String repositoryPath, ObjectId revision) throws IOException {
        ensureRepoIsClean();
        ObjectId repositoryRevision = getFileRevision(repositoryPath, currentHead());
        if (repositoryRevision.equals(revision)) {
            updateAndCommitFile(sourceFile, repositoryPath);
            return true;
        }
        return false;
    }

    public void setBranchAndGetLatest() throws IOException {
        ensureRepoIsClean();
        try {
            // Point a "marker" branch to the current head, so that we know a good starting commit
            // for merge conflict branches.
            git.branchCreate().setName("orgzly-pre-sync-marker").setForce(true).call();
        } catch (GitAPIException e) {
            throw new IOException(context.getString(R.string.git_sync_error_failed_set_marker_branch));
        }
        fetch();
        try {
            RevCommit current = currentHead();
            RevCommit mergeTarget = getCommit(
                    String.format("%s/%s", preferences.remoteName(), git.getRepository().getBranch()));
            if (mergeTarget != null) {
                if (doMerge(mergeTarget)) {  // Try to merge with the remote head of the current branch.
                    if (!git.getRepository().getBranch().equals(preferences.branchName())) {
                        // We are not on the main branch. Make an attempt to return to it.
                        attemptReturnToMainBranch();
                    }
                } else {
                    throw new IOException(String.format("Failed to merge %s and %s",
                            current.getName(), mergeTarget.getName()));
                }
            } else {
                // We failed to find a corresponding remote head. Check if the repo is completely
                // empty, and if so, push to it.
                pushToRemoteIfEmpty();
            }
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage());
        }
    }

    private void pushToRemoteIfEmpty() throws GitAPIException {
        List<Ref> remoteBranches = git.branchList()
                .setListMode(ListBranchCommand.ListMode.REMOTE)
                .call();
        if (remoteBranches.isEmpty()) {
            tryPush();
        }
    }

    public boolean attemptReturnToMainBranch() throws IOException {
        ensureRepoIsClean();
        String originalBranch = git.getRepository().getBranch();
        RevCommit mergeTarget = getCommit(
                String.format("%s/%s", preferences.remoteName(), preferences.branchName()));
        boolean backOnMainBranch = false;
        try {
            if (doMerge(mergeTarget)) {
                RevCommit merged = currentHead();
                checkoutSelected();
                if (doMerge(merged)) {
                    backOnMainBranch = true;
                    git.branchDelete().setBranchNames(originalBranch);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (!backOnMainBranch) {
            try {
                git.checkout().setName(originalBranch).call();
            } catch (GitAPIException ge) {
                ge.printStackTrace();
                throw new IOException("Error during checkout after failed merge attempt.");
            }
        }
        return backOnMainBranch;
    }

    public void updateAndCommitExistingFile(File sourceFile, String repositoryPath) throws IOException {
        ensureRepoIsClean();
        File destinationFile = repoDirectoryFile(repositoryPath);
        if (!destinationFile.exists()) {
            throw new FileNotFoundException("File " + destinationFile + " does not exist");
        }
        updateAndCommitFile(sourceFile, repositoryPath);
    }

    /**
     * Add a new file to the repository, while ensuring that it didn't already exist.
     * @param sourceFile This will become the contents of the added file
     * @param repositoryPath Path inside the repo where the file should be added
     * @throws IOException If the file already exists
     */
    public void addAndCommitNewFile(File sourceFile, String repositoryPath) throws IOException {
        ensureRepoIsClean();
        File destinationFile = repoDirectoryFile(repositoryPath);
        if (destinationFile.exists()) {
            throw new IOException("Can't add new file " + repositoryPath + " that already exists.");
        }
        updateAndCommitFile(sourceFile, repositoryPath);
    }

    private void updateAndCommitFile(
            File sourceFile, String repositoryPath) throws IOException {
        File destinationFile = repoDirectoryFile(repositoryPath);
        MiscUtils.copyFile(sourceFile, destinationFile);
        try {
            git.add().addFilepattern(repositoryPath).call();
            if (!gitRepoIsClean())
                commit(String.format("Orgzly update: %s", repositoryPath));
        } catch (GitAPIException e) {
            throw new IOException("Failed to commit changes.");
        }
    }

    private void commit(String message) throws GitAPIException {
        git.commit().setMessage(message).call();
    }

    public RevCommit currentHead() throws IOException {
        return getCommit(Constants.HEAD);
    }

    public RevCommit getCommit(String identifier) throws IOException {
        if (isEmptyRepo()) {
            return null;
        }
        Ref target = git.getRepository().findRef(identifier);
        if (target == null) {
            return null;
        }
        return new RevWalk(git.getRepository()).parseCommit(target.getObjectId());
    }

    public RevCommit getLatestCommitOfFile(Uri uri) throws GitAPIException {
        String fileName = uri.toString();
        if (fileName.startsWith("/")) {
            fileName = fileName.replaceFirst("/", "");
        }
        return git.log().setMaxCount(1).addPath(fileName).call().iterator().next();
    }

    public String repoPath() {
        return git.getRepository().getWorkTree().getAbsolutePath();
    }

    private boolean gitRepoIsClean() {
        try {
            Status status = git.status().call();
            return !status.hasUncommittedChanges();
        } catch (GitAPIException e) {
            return false;
        }
    }

    private void ensureRepoIsClean() throws IOException {
        if (!gitRepoIsClean())
            throw new IOException("Refusing to update because there are uncommitted changes.");
    }

    public File repoDirectoryFile(String filePath) {
        return new File(repoPath(), filePath);
    }

    public boolean isEmptyRepo() throws IOException{
        return git.getRepository().exactRef(Constants.HEAD).getObjectId() == null;
    }

    public ObjectId getFileRevision(String pathString, RevCommit commit) throws IOException {
        return TreeWalk.forPath(
                git.getRepository(), pathString, commit.getTree()).getObjectId(0);
    }

    public boolean fileMatchesInRevisions(String pathString, RevCommit start, RevCommit end)
            throws IOException {
        return getFileRevision(pathString, start).equals(getFileRevision(pathString, end));
    }
}
