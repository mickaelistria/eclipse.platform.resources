/*******************************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.internal.localstore;

import java.io.File;
import java.io.InputStream;
import java.util.*;
import org.eclipse.core.internal.localstore.Bucket.Entry;
import org.eclipse.core.internal.localstore.HistoryBucket.HistoryEntry;
import org.eclipse.core.internal.resources.*;
import org.eclipse.core.internal.utils.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;

public class HistoryStore2 implements IHistoryStore {

	class HistoryCopyVisitor extends Bucket.Visitor {
		private List changes = new ArrayList();
		private IPath destination;
		private IPath source;

		public HistoryCopyVisitor(IPath source, IPath destination) {
			this.source = source;
			this.destination = destination;
		}

		public void afterSaving(Bucket bucket) throws CoreException {
			saveChanges((HistoryBucket) bucket);
			changes.clear();				
		}

		private void saveChanges(HistoryBucket bucket) throws CoreException {
			if (changes.isEmpty())
				return;
			// make effective all changes collected
			Iterator i = changes.iterator();
			HistoryEntry entry = (HistoryEntry) i.next();
			bucket.load(tree.locationFor(entry.getPath()));
			bucket.addBlobs(entry);
			while (i.hasNext())
				bucket.addBlobs((HistoryEntry) i.next());
			bucket.save();
		}

		public int visit(Entry sourceEntry) {
			IPath destinationPath = destination.append(sourceEntry.getPath().removeFirstSegments(source.segmentCount()));
			HistoryEntry destinationEntry = new HistoryEntry(destinationPath, (HistoryEntry) sourceEntry);
			// we may be copying to the same source bucket, collect to make change effective later
			// since we cannot make changes to it while iterating
			changes.add(destinationEntry);
			return CONTINUE;
		}
	}
	private static final String INDEX_STORE = ".buckets"; //$NON-NLS-1$
	private BlobStore blobStore;
	private Set blobsToRemove = new HashSet();
	private File indexLocation;
	private BucketTree tree;
	private Workspace workspace;

	public HistoryStore2(Workspace workspace, IPath location, int limit) {
		this.workspace = workspace;
		location.toFile().mkdirs();
		this.blobStore = new BlobStore(location, limit);
		this.indexLocation = location.append(INDEX_STORE).toFile();
		this.tree = new BucketTree(indexLocation, createBucketTable());
	}

	/**
	 * @see IHistoryStore#addState(IPath, File, long, boolean)
	 */
	public IFileState addState(IPath key, java.io.File localFile, long lastModified, boolean moveContents) {
		if (Policy.DEBUG_HISTORY)
			System.out.println("History: Adding state for key: " + key + ", file: " + localFile + ", timestamp: " + lastModified + ", size: " + localFile.length()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		if (!isValid(localFile))
			return null;
		UniversalUniqueIdentifier uuid = null;
		try {
			uuid = blobStore.addBlob(localFile, moveContents);
			File bucketDir = tree.locationFor(key);
			HistoryBucket currentBucket = (HistoryBucket) tree.getCurrent();
			currentBucket.load(bucketDir);
			currentBucket.addBlob(key, uuid, lastModified);
			currentBucket.save();
		} catch (CoreException e) {
			ResourcesPlugin.getPlugin().getLog().log(e.getStatus());
		}
		return new FileState(this, key, lastModified, uuid);
	}

	public Set allFiles(IPath root, int depth, IProgressMonitor monitor) {
		final Set allFiles = new HashSet();
		try {
			tree.accept(new Bucket.Visitor() {
				public int visit(Entry fileEntry) {
					allFiles.add(fileEntry.getPath());
					return CONTINUE;
				}
			}, root, depth == IResource.DEPTH_INFINITE ? BucketTree.DEPTH_INFINITE : depth);
		} catch (CoreException e) {
			ResourcesPlugin.getPlugin().getLog().log(e.getStatus());
		}
		return allFiles;
	}

	/**
	 * Applies the clean-up policy to an entry.
	 */
	void applyPolicy(HistoryEntry fileEntry, int maxStates, long minTimeStamp) {
		for (int i = 0; i < fileEntry.getOccurrences(); i++) {
			if (i < maxStates && fileEntry.getTimestamp(i) >= minTimeStamp)
				continue;
			// "delete" the current uuid						
			blobsToRemove.add(fileEntry.getUUID(i));
			fileEntry.deleteOccurrence(i);
		}
	}

	/**
	 * Applies the clean-up policy to a subtree.
	 */
	private void applyPolicy(IPath root) throws CoreException {
		IWorkspaceDescription description = workspace.internalGetDescription();
		final long minimumTimestamp = System.currentTimeMillis() - description.getFileStateLongevity();
		final int maxStates = description.getMaxFileStates();
		// apply policy to the given tree		
		tree.accept(new Bucket.Visitor() {
			public int visit(Entry entry) {
				applyPolicy((HistoryEntry) entry, maxStates, minimumTimestamp);
				return CONTINUE;
			}
		}, root, BucketTree.DEPTH_INFINITE);
		tree.getCurrent().save();
	}

	public void clean(IProgressMonitor monitor) {
		long start = System.currentTimeMillis();
		try {
			IWorkspaceDescription description = workspace.internalGetDescription();
			final long minimumTimestamp = System.currentTimeMillis() - description.getFileStateLongevity();
			final int maxStates = description.getMaxFileStates();
			final int[] entryCount = new int[1];
			tree.accept(new Bucket.Visitor() {
				public int visit(Entry fileEntry) {
					entryCount[0] += fileEntry.getOccurrences();
					applyPolicy((HistoryEntry) fileEntry, maxStates, minimumTimestamp);
					return CONTINUE;
				}
			}, Path.ROOT, BucketTree.DEPTH_INFINITE);
			if (Policy.DEBUG_HISTORY) {
				Policy.debug("Time to apply history store policies: " + (System.currentTimeMillis() - start) + "ms."); //$NON-NLS-1$ //$NON-NLS-2$
				Policy.debug("Total number of history store entries: " + entryCount[0]); //$NON-NLS-1$
			}
			start = System.currentTimeMillis();
			// remove unreferenced blobs
			blobStore.deleteBlobs(blobsToRemove);
			if (Policy.DEBUG_HISTORY)
				Policy.debug("Time to remove " + blobsToRemove.size() + " unreferenced blobs: " + (System.currentTimeMillis() - start) + "ms."); //$NON-NLS-1$//$NON-NLS-2$ //$NON-NLS-3$			
			blobsToRemove = new HashSet();
		} catch (Exception e) {
			String message = Policy.bind("history.problemsCleaning"); //$NON-NLS-1$
			ResourceStatus status = new ResourceStatus(IResourceStatus.FAILED_DELETE_LOCAL, null, message, e);
			ResourcesPlugin.getPlugin().getLog().log(status);
		}
	}

	public void copyHistory(IResource sourceResource, IResource destinationResource) {
		// return early if either of the paths are null or if the source and
		// destination are the same.
		if (sourceResource == null || destinationResource == null) {
			String message = Policy.bind("history.copyToNull"); //$NON-NLS-1$
			ResourceStatus status = new ResourceStatus(IResourceStatus.INTERNAL_ERROR, null, message, null);
			ResourcesPlugin.getPlugin().getLog().log(status);
			return;
		}
		if (sourceResource.equals(destinationResource)) {
			String message = Policy.bind("history.copyToSelf"); //$NON-NLS-1$
			ResourceStatus status = new ResourceStatus(IResourceStatus.INTERNAL_ERROR, sourceResource.getFullPath(), message, null);
			ResourcesPlugin.getPlugin().getLog().log(status);
			return;
		}

		final IPath source = sourceResource.getFullPath();
		final IPath destination = destinationResource.getFullPath();
		Assert.isLegal(source.segmentCount() > 0);
		Assert.isLegal(destination.segmentCount() > 0);
		Assert.isLegal(source.segmentCount() > 1 || destination.segmentCount() == 1);

		try {
			// copy history by visiting the source tree
			HistoryCopyVisitor copyVisitor = new HistoryCopyVisitor(source, destination);
			tree.accept(copyVisitor, source, BucketTree.DEPTH_INFINITE);
			// apply clean-up policy to the destination tree 
			applyPolicy(destinationResource.getFullPath());
		} catch (CoreException e) {
			ResourcesPlugin.getPlugin().getLog().log(e.getStatus());
		}
	}

	HistoryBucket createBucketTable() {
		return new HistoryBucket(indexLocation);
	}

	public boolean exists(IFileState target) {
		return blobStore.fileFor(((FileState) target).getUUID()).exists();
	}

	public InputStream getContents(IFileState target) throws CoreException {
		if (!target.exists()) {
			String message = Policy.bind("history.notValid"); //$NON-NLS-1$
			throw new ResourceException(IResourceStatus.FAILED_READ_LOCAL, target.getFullPath(), message, null);
		}
		return blobStore.getBlob(((FileState) target).getUUID());
	}

	public File getFileFor(IFileState state) {
		return blobStore.fileFor(((FileState) state).getUUID());
	}

	public IFileState[] getStates(IPath filePath, IProgressMonitor monitor) {
		File bucketDir = tree.locationFor(filePath);
		try {
			HistoryBucket currentBucket = (HistoryBucket) tree.getCurrent();
			currentBucket.load(bucketDir);
			HistoryEntry fileEntry = currentBucket.getEntry(filePath);
			if (fileEntry == null || fileEntry.isEmpty())
				return new IFileState[0];
			IFileState[] states = new IFileState[fileEntry.getOccurrences()];
			for (int i = 0; i < states.length; i++)
				states[i] = new FileState(this, fileEntry.getPath(), fileEntry.getTimestamp(i), fileEntry.getUUID(i));
			return states;
		} catch (CoreException ce) {
			ResourcesPlugin.getPlugin().getLog().log(ce.getStatus());
			return new IFileState[0];
		}
	}

	public BucketTree getTree() {
		return tree;
	}

	/**
	 * Return a boolean value indicating whether or not the given file
	 * should be added to the history store based on the current history
	 * store policies.
	 * 
	 * @param localFile the file to check
	 * @return <code>true</code> if this file should be added to the history
	 * 	store and <code>false</code> otherwise
	 */
	private boolean isValid(java.io.File localFile) {
		WorkspaceDescription description = workspace.internalGetDescription();
		boolean result = localFile.length() <= description.getMaxFileStateSize();
		if (Policy.DEBUG_HISTORY && !result)
			System.out.println("History: Ignoring file (too large). File: " + localFile.getAbsolutePath() + //$NON-NLS-1$
					", size: " + localFile.length() + //$NON-NLS-1$
					", max: " + description.getMaxFileStateSize()); //$NON-NLS-1$
		return result;
	}

	public void remove(IPath root, IProgressMonitor monitor) {
		try {
			final Set tmpBlobsToRemove = blobsToRemove;
			tree.accept(new Bucket.Visitor() {
				public int visit(Entry fileEntry) {
					for (int i = 0; i < fileEntry.getOccurrences(); i++)
						// remember we need to delete the files later
						tmpBlobsToRemove.add(((HistoryEntry) fileEntry).getUUID(i));
					fileEntry.delete();
					return CONTINUE;
				}
			}, root, BucketTree.DEPTH_INFINITE);
		} catch (CoreException ce) {
			ResourcesPlugin.getPlugin().getLog().log(ce.getStatus());
		}
	}

	/**
	 * @see IHistoryStore#removeGarbage()
	 */
	public void removeGarbage() {
		try {
			final Set tmpBlobsToRemove = blobsToRemove;
			tree.accept(new Bucket.Visitor() {
				public int visit(Entry fileEntry) {
					for (int i = 0; i < fileEntry.getOccurrences(); i++)
						// remember we need to delete the files later
						tmpBlobsToRemove.remove(((HistoryEntry) fileEntry).getUUID(i));
					return CONTINUE;
				}
			}, Path.ROOT, BucketTree.DEPTH_INFINITE);
			blobStore.deleteBlobs(blobsToRemove);
			blobsToRemove = new HashSet();
		} catch (Exception e) {
			String message = Policy.bind("history.problemsCleaning"); //$NON-NLS-1$
			ResourceStatus status = new ResourceStatus(IResourceStatus.FAILED_DELETE_LOCAL, null, message, e);
			ResourcesPlugin.getPlugin().getLog().log(status);
		}
	}

	public void shutdown(IProgressMonitor monitor) throws CoreException {
		tree.close();
	}

	public void startup(IProgressMonitor monitor) {
		// nothing to be done
	}
}