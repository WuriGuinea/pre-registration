package io.mosip.registration.processor.filesystem.ceph.adapter.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import io.findify.s3mock.S3Mock;
import io.mosip.registration.processor.core.spi.filesystem.adapter.FileSystemAdapter;
import io.mosip.registration.processor.filesystem.ceph.adapter.impl.exception.ConnectionUnavailableException;
import io.mosip.registration.processor.filesystem.ceph.adapter.impl.exception.InvalidConnectionParameters;
import io.mosip.registration.processor.filesystem.ceph.adapter.impl.exception.PacketNotFoundException;
import io.mosip.registration.processor.filesystem.ceph.adapter.impl.utils.ConnectionUtil;
import io.mosip.registration.processor.filesystem.ceph.adapter.impl.utils.PacketFiles;

/**
 * This class tests the functionalities of DFSAdapterImpl.
 *
 * @author Pranav Kumar
 * @author Ranjitha
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(ConnectionUtil.class)
@PowerMockIgnore({ "javax.management.*", "javax.net.ssl.*" })
public class FilesystemCephAdapterImplTest {

	/** The api. */
	private S3Mock api;

	/** The client. */
	private AmazonS3 client;

	/** The check enrolment id. */
	private String checkEnrolmentId;

	/** The file extension. */
	private String fileExtension;

	/** The Constant CONFIG_FILE_NAME. */
	private static final String CONFIG_FILE_NAME = "config.properties";

	/** The dfs adapter. */
	private FileSystemAdapter<InputStream, PacketFiles, Boolean> dfsAdapter;

	/** The Constant FAILURE_ENROLMENT_ID. */
	private static final String FAILURE_ENROLMENT_ID = "1234";

	/**
	 * This method sets up the required configuration before execution of test
	 * cases.
	 *
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	@Before
	public void setup() throws IOException {
		Properties properties = new Properties();
		InputStream inputStream;
		inputStream = ConnectionUtil.class.getClassLoader().getResourceAsStream(CONFIG_FILE_NAME);
			properties.load(inputStream);
		this.checkEnrolmentId = properties.getProperty("check.enrolment.id");
		this.fileExtension = properties.getProperty("file.extension");
		api = new S3Mock.Builder().withPort(8001).withInMemoryBackend().build();
		api.start();
		EndpointConfiguration endpoint = new EndpointConfiguration("http://localhost:8001", "us-west-2");
		client = AmazonS3ClientBuilder.standard().withPathStyleAccessEnabled(true).withEndpointConfiguration(endpoint)
				.withCredentials(new AWSStaticCredentialsProvider(new AnonymousAWSCredentials())).build();
		PowerMockito.mockStatic(ConnectionUtil.class);
		when(ConnectionUtil.getConnection()).thenReturn(client);

		// Putting a file to mocked ceph instance
		this.dfsAdapter = new FilesystemCephAdapterImpl();
		ClassLoader classLoader = getClass().getClassLoader();
		String filePath = classLoader.getResource(checkEnrolmentId + fileExtension).getFile();
		File packet = new File(filePath);
		dfsAdapter.storePacket(this.checkEnrolmentId, packet);
	}

	/**
	 * This method tests uploading of a packet to DFS.
	 *
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	@Test
	public void testUploadPacketSuccess() throws IOException {
		ClassLoader classLoader = getClass().getClassLoader();
		String filePath = classLoader.getResource(checkEnrolmentId + fileExtension).getFile();
		File packet = new File(filePath);
		boolean result = this.dfsAdapter.storePacket(this.checkEnrolmentId, packet);
		assertEquals("Successfully uploaded packet as filepath to DFS .",true, result);
	}

	/**
	 * This method tests getting a packet successfully from DFS.
	 *
	 * @return the packet success test
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	@Test
	public void testGetPacketSuccess() throws IOException {
		ClassLoader classLoader = getClass().getClassLoader();
		String filePath = classLoader.getResource(checkEnrolmentId + fileExtension).getFile();
		InputStream packet = dfsAdapter.getPacket(checkEnrolmentId);
		ZipInputStream zis = new ZipInputStream(packet);
		ZipEntry ze = zis.getNextEntry();
		int actualFileCount = 0;
		while (ze != null) {
			actualFileCount++;
			ze = zis.getNextEntry();
		}
		ZipFile actualZipFile = new ZipFile(filePath);
		Enumeration<? extends ZipEntry> entries = actualZipFile.entries();
		int expectedFileCount = 0;
		while (entries.hasMoreElements()) {
			expectedFileCount++;
			entries.nextElement();
		}
		assertEquals("Should get packet for input registration id.", expectedFileCount, actualFileCount);
		actualZipFile.close();
	}

	/**
	 * This method tests getting a packet which is not present in DFS.
	 *
	 * @return the packet failure test
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	@Test(expected = PacketNotFoundException.class)
	public void testGetPacketFailure() throws IOException {
		this.dfsAdapter.unpackPacket(FAILURE_ENROLMENT_ID);
	}

	/**
	 * This method checks unpacking of a packet in DFS.
	 *
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	@Test
	public void testUnpackPacketFailure() throws IOException {
		this.dfsAdapter.unpackPacket(checkEnrolmentId);
		int noOfextractedFiles = this.client.listObjects(this.checkEnrolmentId).getObjectSummaries().size();
		assertEquals("Check the count of extracted file.", 5, noOfextractedFiles);
		// check file exists test
		boolean findResult = this.dfsAdapter.checkFileExistence(checkEnrolmentId, PacketFiles.BIOMETRIC.name());
		assertEquals("To check file exist or not by registration id.", true, findResult);
		// Get file test
		InputStream file = this.dfsAdapter.getFile(checkEnrolmentId, PacketFiles.DEMOGRAPHIC.name());
		assertNotNull("Get file as inpustream by registration id .",file);
		// Delete file test
		boolean result = this.dfsAdapter.deleteFile(checkEnrolmentId, PacketFiles.DEMOGRAPHIC.name());
		assertEquals("Delete file by registration id .",true, result);
	}

	/**
	 * This method checks fetching a file from a packet that is not present in DFS.
	 *
	 * @return the file failure test
	 */
	@Test(expected = PacketNotFoundException.class)
	public void testGetFileFailure() {
		this.dfsAdapter.getFile(FAILURE_ENROLMENT_ID, PacketFiles.DEMOGRAPHIC.name());
	}

	/**
	 * This method checks deleting a packet from DFS and later fetching it.
	 */
	@Test(expected = PacketNotFoundException.class)
	public void testDeletePacketFailure() {
		this.dfsAdapter.deletePacket(checkEnrolmentId);
		this.dfsAdapter.getPacket(checkEnrolmentId);
	}

	/**
	 * Store packet input stream success test.
	 */
	@Test
	public void testStorePacketInputStreamSuccess() {
		InputStream packet = dfsAdapter.getPacket(checkEnrolmentId);
		boolean result = this.dfsAdapter.storePacket(this.checkEnrolmentId, packet);
		assertEquals("Successfully uploaded packet as inputstream to DFS.",true, result);
	}

	/**
	 * Store packet connection unavailable exception test.
	 */
	@Test(expected = ConnectionUnavailableException.class)
	public void testStorePacketConnectionUnavailableException() {
		EndpointConfiguration endpoint = new EndpointConfiguration("http://localhost:8001", "us-west-2");
		AmazonS3 client1 = AmazonS3ClientBuilder.standard().withPathStyleAccessEnabled(true)
				.withEndpointConfiguration(endpoint).withCredentials(null).build();
		PowerMockito.mockStatic(ConnectionUtil.class);
		when(ConnectionUtil.getConnection()).thenReturn(client1);
		this.dfsAdapter = new FilesystemCephAdapterImpl();
		InputStream packet = new InputStream() {
			@Override
			public int read() throws IOException {
				return 0;
			}
		};
		this.dfsAdapter.storePacket(this.checkEnrolmentId, packet);
	}

	/**
	 * Store packet invalid connection parameter exception test.
	 */
	@SuppressWarnings("unchecked")
	@Test(expected = InvalidConnectionParameters.class)
	public void testStorePacketInvalidConnectionParameterException() {
		PowerMockito.mockStatic(ConnectionUtil.class);
		when(ConnectionUtil.getConnection()).thenThrow(InvalidConnectionParameters.class);
		InputStream packet = new InputStream() {
			@Override
			public int read() throws IOException {
				return 0;
			}
		};
		this.dfsAdapter = new FilesystemCephAdapterImpl();
		this.dfsAdapter.storePacket(this.checkEnrolmentId, packet);
	}

	/**
	 * This method destroys the dummy DFS connection.
	 */
	@After
	public void destroy() {
		api.stop();
	}

}