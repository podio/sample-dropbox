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

/**
 * Scans for files in the API and uploads to Dropbox.
 * 
 * Was done in 2 hours as part of the Podio API hackaton
 * 
 * @author Team Dropbox
 */
public class FileScanner {

	/**
	 * The temporary directory the files will be written to
	 */
	private static final String TMP_DIR = System.getProperty("java.io.tmpdir");

	/**
	 * The page size to use when quering files in the Podio
	 */
	private static final int PAGE_SIZE = 100;

	/**
	 * The main Podio API
	 */
	private final APIFactory podioAPIFactory;
	/**
	 * Dropbox API
	 */
	private final DropboxAPI dropBoxAPI;
	/**
	 * The root folder in Dropbox, must be dropbox or sandbox
	 */
	private final String root;
	/**
	 * The main folder to put the files in
	 */
	private final String main;
	/**
	 * The organization to synchronize
	 */
	private final String organization;

	/**
	 * The cache for existing files
	 */
	private final Map<String, List<String>> fileMap = new HashMap<String, List<String>>();

	/**
	 * We limit uploads to not let it go crazy
	 */
	private int uploadsLeft = 10;

	/**
	 * Create a new scanner with configuration from config.properties
	 * 
	 * @throws IOException
	 *             If there was an error loading the configuration file
	 */
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

	/**
	 * Scans the files in Podio and upload new files
	 */
	public void scan() throws IOException {
		List<OrganizationWithSpaces> organizations = podioAPIFactory
				.getOrgAPI().getOrganizations();
		for (OrganizationWithSpaces organization : organizations) {
			if (organization.getName().equals(this.organization)) {
				System.out.println("Processing organization "
						+ organization.getName());
				List<SpaceMini> spaces = organization.getSpaces();
				for (SpaceMini space : spaces) {
					System.out.println("Processing space " + space.getName());

					scanSpace(organization, space);
				}
			}
		}
	}

	/**
	 * Scans the specific space and uploads any files found
	 */
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

				if (uploaded) {
					uploadsLeft--;
					if (uploadsLeft == 0) {
						System.out
								.println("File limit reached, stopping for now");
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

	/**
	 * Checks if the file is already uploaded
	 */
	private boolean exists(String folder, String name) {
		return getExistingFiles(folder).contains(name);
	}

	/**
	 * Gets the existing files in a folder
	 * 
	 * Cached to limit calls to the Dropbox API
	 */
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

	/**
	 * Uploads the file to dropbox
	 */
	private boolean upload(String spaceFolder, File file) throws IOException {
		FilePath path = getPath(file);
		if (path != null) {
			String fileFolder = spaceFolder;
			if (path.getPath() != null) {
				fileFolder += path.getPath() + "/";
			}

			System.out.println("Processing file " + path.getName());

			if (exists(fileFolder, path.getName())) {
				System.out.println("Skipping file, already uploaded");
				return false;
			}

			System.out.println("Downloading file to local storage");

			java.io.File tmpFile = new java.io.File(TMP_DIR + "/"
					+ path.getName());

			podioAPIFactory.getFileAPI().downloadFile(file.getId(), tmpFile);

			System.out.println("Uplading file to Dropbox, stand by");

			dropBoxAPI.putFile(root, fileFolder, tmpFile);

			return true;
		} else {
			return false;
		}
	}

	/**
	 * Returns the path and filename to use for a file
	 */
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

	/**
	 * Small helper class to hold path and file name
	 */
	private static final class FilePath {

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

	/**
	 * Starts the updater and uploads files to Dropbox
	 */
	public static void main(String[] args) throws IOException {
		new FileScanner().scan();
	}
}
