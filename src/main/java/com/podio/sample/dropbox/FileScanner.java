package com.podio.sample.dropbox;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.dropbox.client.DropboxAPI;
import com.dropbox.client.DropboxAPI.Config;
import com.dropbox.client.DropboxAPI.Entry;
import com.podio.APIFactory;
import com.podio.ResourceFactory;
import com.podio.common.ReferenceType;
import com.podio.file.File;
import com.podio.item.Item;
import com.podio.oauth.OAuthClientCredentials;
import com.podio.oauth.OAuthUsernameCredentials;
import com.podio.org.OrganizationWithSpaces;
import com.podio.space.SpaceMini;

public class FileScanner {

	private static final String TMP_DIR = System.getProperty("java.io.tmpdir");

	private static final int PAGE_SIZE = 100;

	private final APIFactory podioAPIFactory;
	private final DropboxAPI dropBoxAPI;
	private final String root;
	private final String main;
	private final String organization;

	private final Map<String, List<String>> fileMap = new HashMap<String, List<String>>();

	private int uploadsLeft = 10;

	public FileScanner() throws IOException {
		Properties properties = new Properties();
		properties.load(new FileInputStream("config.properties"));

		ResourceFactory resourceFactory = new ResourceFactory(
				new OAuthClientCredentials(
						properties.getProperty("podio.client.mail"),
						properties.getProperty("podio.client.secret")),
				new OAuthUsernameCredentials(properties
						.getProperty("podio.user.mail"), properties
						.getProperty("podio.user.password")));

		this.podioAPIFactory = new APIFactory(resourceFactory);

		this.dropBoxAPI = new DropboxAPI();

		Config config = dropBoxAPI.getConfig(
				getClass().getResourceAsStream("dropbox.config"), false);
		dropBoxAPI.authenticate(config, "phil@podio.com", "password");

		this.root = properties.getProperty("dropbox.folder.root");
		this.main = properties.getProperty("dropbox.folder.main");

		this.organization = properties.getProperty("podio.organization");
		System.out.println(organization);
	}

	public void scan() throws IOException {
		List<OrganizationWithSpaces> organizations = podioAPIFactory
				.getOrgAPI().getOrganizations();
		for (OrganizationWithSpaces organization : organizations) {
			if (organization.getName().equals(this.organization)) {
				System.out.println(organization.getName());
				List<SpaceMini> spaces = organization.getSpaces();
				for (SpaceMini space : spaces) {
					System.out.println(" - " + space.getName());

					scanSpace(organization, space);
				}
			}
		}
	}

	private void scanSpace(OrganizationWithSpaces organization, SpaceMini space)
			throws IOException {
		String spaceFolder = "/" + main + "/" + organization.getName() + "/"
				+ space.getName() + "/";

		int offset = 0;
		while (true) {
			List<File> files = podioAPIFactory.getFileAPI().getOnSpace(
					space.getId(), PAGE_SIZE, offset);
			System.out.println(files.size());

			for (File file : files) {
				boolean uploaded = upload(spaceFolder, file);
				System.out.println("Uploaded? " + uploaded);

				if (uploaded) {
					uploadsLeft--;
					if (uploadsLeft == 0) {
						System.out.println("Reached file limit");
						System.exit(0);
					}
				}
			}

			if (files.size() < PAGE_SIZE) {
				return;
			} else {
				offset += PAGE_SIZE;
			}
		}
	}

	private boolean exists(String folder, String name) {
		return getExistingFiles(folder).contains(name);
	}

	private List<String> getExistingFiles(String folder) {
		List<String> list = fileMap.get(folder);
		if (list == null) {
			Entry metadata = dropBoxAPI
					.metadata(root, folder, 1000, null, true);

			List<String> names = new ArrayList<String>();
			List<Entry> contents = metadata.contents;
			if (contents != null) {
				for (Entry content : contents) {
					names.add(content.fileName());
				}
			}

			fileMap.put(folder, names);

			return names;
		} else {
			return list;
		}
	}

	private boolean upload(String spaceFolder, File file) throws IOException {
		FilePath path = getPath(file);
		if (path != null) {
			String fileFolder = spaceFolder;
			if (path.getPath() != null) {
				fileFolder += path.getPath() + "/";
			}

			if (exists(fileFolder, path.getName())) {
				System.out.println("Skipping file " + path.getName());
				return false;
			}

			System.out.println("Uploading " + path.getName());

			java.io.File tmpFile = new java.io.File(TMP_DIR + "/"
					+ path.getName());

			podioAPIFactory.getFileAPI().downloadFile(file.getId(), tmpFile);

			dropBoxAPI.putFile(root, fileFolder, tmpFile);

			return true;
		} else {
			return false;
		}
	}

	private FilePath getPath(File file) {
		String filename = file.getName();
		filename = filename.replace(' ', '_');

		String author = file.getCreatedBy().getName();
		author = author.replace(' ', '_');

		if (file.getData().getType() == ReferenceType.STATUS
				&& file.getContext().getType() == ReferenceType.STATUS) {

			return new FilePath(null, "status[" + file.getId() + "]_by_"
					+ author + "-" + filename);
		} else if (file.getData().getType() == ReferenceType.ITEM
				&& file.getContext().getType() == ReferenceType.ITEM) {
			Item item = podioAPIFactory.getItemAPI().getItem(
					file.getData().getId());

			return new FilePath(item.getApplication().getName(),
					item.getTitle() + "_by_" + author + "-" + filename);
		} else {
			return null;
		}
	}

	private static class FilePath {

		private final String path;

		private final String name;

		public FilePath(String path, String name) {
			super();
			this.path = path;
			this.name = name;
		}

		public String getPath() {
			return path;
		}

		public String getName() {
			return name;
		}
	}

	public static void main(String[] args) throws IOException {
		new FileScanner().scan();
	}
}
