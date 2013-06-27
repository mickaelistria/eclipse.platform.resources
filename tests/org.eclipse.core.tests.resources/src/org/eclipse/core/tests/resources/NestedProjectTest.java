/*******************************************************************************
 * Copyright (c) 2013 Red Hat Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Mickael Istria (Red Hat Inc.) - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.tests.resources;

import java.io.*;
import org.eclipse.core.internal.resources.ResourceException;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;

/**
 *
 */
public class NestedProjectTest extends ResourceTest {

	public void testCreateNestedProjectFromFullPath() throws Exception {
		IProject parentProject = getWorkspace().getRoot().getProject("PROJECT");
		parentProject.create(new NullProgressMonitor());
		IProject nestedProject = getWorkspace().getRoot().getProject(parentProject.getFullPath().append("nested"));
		nestedProject.create(new NullProgressMonitor());
		parentProject.open(new NullProgressMonitor());
		boolean foundAsMember = false;
		for (IResource child : parentProject.members()) {
			if (child.getName().equals("nested")) {
				foundAsMember = true;
				assertEquals("Nested project is not a project", IResource.PROJECT, child.getType());
				assertTrue("Not an IProject", child instanceof IProject);
			}
		}
		assertTrue("Nested member not found", foundAsMember);
		// Test project loaded
		nestedProject = parentProject.getNestedProject("nested");
		assertEquals(parentProject.getLocation().append("nested"), nestedProject.getLocation());
		assertEquals(parentProject.getFullPath().append("nested"), nestedProject.getFullPath());
	}

	public void testCreateNestedProjectFromNestedAccessor() throws Exception {
		IProject parentProject = getWorkspace().getRoot().getProject("PROJECT");
		parentProject.create(new NullProgressMonitor());
		parentProject.open(new NullProgressMonitor());
		IProject nestedProject = parentProject.getNestedProject("nested");
		assertNotNull(nestedProject);
		nestedProject.create(new NullProgressMonitor());;
		assertExistsInFileSystem(nestedProject);
		assertExistsInWorkspace(nestedProject);
		assertEquals(parentProject.getLocation().append("nested"), nestedProject.getLocation());
		assertEquals(parentProject.getFullPath().append("nested"), nestedProject.getFullPath());
	}

	public void testInvalidNestedProject_parentProjectPath() {
		try {
			IProject project = getWorkspace().getRoot().getProject(new Path("/noProject/nested"));
			project.create(new NullProgressMonitor());
			fail("Project should not be created where parent don't exist");
		} catch (IllegalArgumentException ex) {
			// OK
			// Check error message here
		} catch (Exception ex) {
			fail("Expected a CoreException, got", ex);
		}
	}

	public void testInvalidNestedProject_parentFolderPath() throws Exception {
		IProject parentProject = getWorkspace().getRoot().getProject("parent");
		parentProject.create(new NullProgressMonitor());
		parentProject.open(new NullProgressMonitor());
		IProject project = getWorkspace().getRoot().getProject(new Path("/parent/noFolder/project"));
		try {
			project.create(new NullProgressMonitor());
			fail("Project should not be created where parent don't exist");
		} catch (IllegalArgumentException ex) {
			// OK
			// Check error message here
		} catch (Exception ex) {
			fail("Expected a CoreException, got", ex);
		}
	}

	public void testInvalideNestedProject_ExistingResource() throws Exception {
		IProject parentProject = getWorkspace().getRoot().getProject("parent");
		parentProject.create(new NullProgressMonitor());
		parentProject.open(new NullProgressMonitor());
		IFolder nestedFolder = parentProject.getFolder("nested");
		nestedFolder.create(true, true, new NullProgressMonitor());
		IProject nestedProject = parentProject.getNestedProject("nested");
		assertEquals(nestedFolder.getFullPath(), nestedProject.getFullPath());
		try {
			nestedProject.create(new NullProgressMonitor());
			fail("Project should not be created where a resource already exists");
		} catch (ResourceException ex) {
			// OK
			// Check error message here
		} catch (Exception ex) {
			fail("Expected a CoreException, got", ex);
		}
	}

	public void testTurnFolderIntoProject() throws Exception {
		// SETUP
		IProject parentProject = getWorkspace().getRoot().getProject("parent");
		parentProject.create(new NullProgressMonitor());
		parentProject.open(new NullProgressMonitor());
		IFolder nestedFolder = parentProject.getFolder("nested");
		nestedFolder.create(true, true, new NullProgressMonitor());
		IFile contentFile = nestedFolder.getFile("testFile");
		contentFile.create(new ByteArrayInputStream("test".getBytes()), true, new NullProgressMonitor());
		// TESTS
		nestedFolder.turnIntoProject(new NullProgressMonitor());
		// Check folder became a project
		assertFalse(nestedFolder.isAccessible());
		IProject nestedProject = getWorkspace().getRoot().getProject(nestedFolder.getFullPath());
		nestedProject.open(new NullProgressMonitor());
		// check content still here
		//nestedProject.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		contentFile = nestedProject.getFile("testFile");
		assertTrue(contentFile.isAccessible());
		byte[] content = new byte[4];
		contentFile.getContents().read(content);
		assertEquals("test", new String(content));
	}

	public void testTurnExternalFolderIntoProject() throws Exception {
		// Create external projects
		File parentProjectFolder = File.createTempFile("eclipseProject", "test");
		parentProjectFolder.delete();
		parentProjectFolder.mkdir();
		File nestedProjectFolder = new File(parentProjectFolder, "nested");
		nestedProjectFolder.mkdir();
		File nestedProjectContent = new File(nestedProjectFolder, "testFile");
		nestedProjectContent.createNewFile();
		FileOutputStream contentOutputStream = new FileOutputStream(nestedProjectContent);
		contentOutputStream.write("test".getBytes());
		contentOutputStream.close();

		// Import in WS
		IProjectDescription projectDesc = ResourcesPlugin.getWorkspace().newProjectDescription("parent");
		projectDesc.setLocation(new Path(parentProjectFolder.getAbsolutePath()));
		IProject parentProject = ResourcesPlugin.getWorkspace().getRoot().getProject("parent");
		parentProject.create(projectDesc, new NullProgressMonitor());
		parentProject.open(new NullProgressMonitor());
		assertEquals(parentProjectFolder.getAbsolutePath(), parentProject.getLocation().toFile().getAbsolutePath());
		IFolder nestedFolder = parentProject.getFolder("nested");
		assertTrue(nestedFolder.isAccessible());

		// TESTS
		nestedFolder.turnIntoProject(new NullProgressMonitor());
		// Check folder became a project
		assertFalse(nestedFolder.isAccessible());
		IProject nestedProject = getWorkspace().getRoot().getProject(nestedFolder.getFullPath());
		nestedProject.open(new NullProgressMonitor());
		assertEquals(nestedProjectFolder.getAbsolutePath(), nestedProject.getLocation().toFile().getAbsolutePath());
		// check content still here
		IFile contentFile = nestedProject.getFile("testFile");
		assertTrue(contentFile.isAccessible());
		byte[] content = new byte[4];
		contentFile.getContents().read(content);
		assertEquals("test", new String(content));
	}

	/*
	public void testExistingExternalNestedProject() throws Exception {
		File parentProjectFolder = File.createTempFile("eclipseProject", "test");
		parentProjectFolder.delete();
		parentProjectFolder.mkdir();
		File parentProjectFile = new File(parentProjectFolder, ".project");
		parentProjectFile.createNewFile();
		FileOutputStream parentProjectFileStream = new FileOutputStream(parentProjectFile);
		parentProjectFileStream.write("<projectDescription><name>parent</name></projectDescription>".getBytes());
		parentProjectFileStream.close();
		File nestedProjectFolder = new File(parentProjectFolder, "nested");
		nestedProjectFolder.mkdir();
		File nestedProjectFile = new File(nestedProjectFolder, ".project");
		nestedProjectFile.createNewFile();
		FileOutputStream projectFileStream = new FileOutputStream(nestedProjectFile);
		projectFileStream.write("<projectDescription><name>nested</name></projectDescription>".getBytes());
		projectFileStream.close();

		FileInputStream parentProjectInputStream = new FileInputStream(parentProjectFile);
		IProjectDescription projectDesc = ResourcesPlugin.getWorkspace().loadProjectDescription(parentProjectInputStream);
		parentProjectInputStream.close();
		projectDesc.setLocation(new Path(parentProjectFolder.getAbsolutePath()));
		IProject parentProject = ResourcesPlugin.getWorkspace().getRoot().getProject("parent");
		parentProject.create(projectDesc, new NullProgressMonitor());
		parentProject.open(new NullProgressMonitor());
		parentProject.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		// Test project is a member
		boolean foundAsMember = false;
		for (IResource child : parentProject.members()) {
			if (child.getName().equals("nested")) {
				foundAsMember = true;
				assertEquals("Nested project is not a project", IResource.PROJECT, child.getType());
				assertTrue("Not an IProject", child instanceof IProject);
			}
		}
		assertTrue("Nested member not found", foundAsMember);
		// Test project loaded
		assertEquals(parentProjectFolder.getAbsolutePath(), parentProject.getLocation().toFile().getAbsolutePath());
		IProject nestedProject = parentProject.getNestedProject("nested");
		nestedProject.getDescription().getLocationURI()
		assertEquals(parentProject.getLocation().append("nested"), nestedProject.getLocation());
		assertEquals(parentProject.getFullPath().append("nested"), nestedProject.getFullPath());
	}

	*/

}
