package cloudlab.ops;

import cloudlab.GenericOpsProto.GenericOpsGrpc.GenericOps;
import cloudlab.GenericOpsProto.GenericReply;
import cloudlab.GenericOpsProto.GenericRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import io.grpc.stub.StreamObserver;

/**
 * This class implements the GenericOps service interface generated by gRPC
 * using the .proto file defined. Connects to the target VM and issues command
 * via jsch
 */

public class GenericOpsImpl implements GenericOps {

	private static final Logger logger = Logger.getLogger(GenericOpsImpl.class.getName());

	JSch jsch = new JSch();
	String fileSeparator = System.getProperty("file.separator");
	byte[] tmp = new byte[1024];
	AWSCredentials credentials = new ProfileCredentialsProvider().getCredentials();
	AmazonS3 s3 = new AmazonS3Client(credentials);

	@Override
	public void create(GenericRequest request, StreamObserver<GenericReply> responseObserver) {
		StringBuilder output = new StringBuilder();
		try {
			String object = request.getCredentials() + ".pem";
			S3Object s3Object = s3.getObject(new GetObjectRequest(request.getBucketName(), object));
			InputStream inputStream = s3Object.getObjectContent();

			File outputFile = new File(
					System.getProperty("user.dir") + fileSeparator + request.getCredentials() + ".pem");
			OutputStream outputStream = new FileOutputStream(outputFile);
			int read = 0;
			byte[] bytes = new byte[1024];

			while ((read = inputStream.read(bytes)) != -1) {
				outputStream.write(bytes, 0, read);
			}

			jsch.addIdentity(outputFile.getAbsolutePath());
			jsch.setConfig("StrictHostKeyChecking", "no");

			String url = request.getInstallFile();
			String repoNameGit = url.split("/")[url.split("/").length - 1];
			String repoName = repoNameGit.substring(0, repoNameGit.indexOf(".git"));

			String moduleName = request.getModuleName();
			String moduleDirectory = moduleName.split("-")[moduleName.split("-").length - 1];

			// Connect to the EC2 instance
			Session session = jsch.getSession(request.getUsername(), request.getPublicIP(), 22);
			session.connect();

			String command = "echo Executing: sudo apt-get -y update && sudo apt-get -y update"
					+ " && echo Executing: sudo wget https://apt.puppetlabs.com/puppetlabs-release-trusty.deb && sudo wget https://apt.puppetlabs.com/puppetlabs-release-trusty.deb"
					+ " && echo Executing: sudo dpkg -i puppetlabs-release-trusty.deb && sudo dpkg -i puppetlabs-release-trusty.deb"
					+ " && echo Executing: sudo apt-get -y install puppet && sudo apt-get -y install puppet"
					+ " && echo Executing: sudo puppet module install " + request.getModuleName()
					+ " && sudo puppet module install " + request.getModuleName()
					+ " && echo Executing: sudo apt-get -y install git && sudo apt-get -y install git"
					+ " && echo Executing: sudo git clone " + request.getInstallFile() + " && sudo git clone "
					+ request.getInstallFile() + " && sudo cp " + repoName + "/* /etc/puppet/modules/" + moduleDirectory
					+ " && echo Executing: cd /etc/puppet/modules/" + moduleDirectory + " && cd /etc/puppet/modules/"
					+ moduleDirectory
					+ " && echo Executing: sudo puppet apply install.pp && sudo puppet apply install.pp";

			Channel channel = session.openChannel("exec");
			((ChannelExec) channel).setCommand(command);
			((ChannelExec) channel).setErrStream(System.err);
			channel.connect();

			InputStream input = channel.getInputStream();
			// start reading the input from the executed commands on the shell

			while (true) {
				while (input.available() > 0) {
					int i = input.read(tmp, 0, 1024);
					if (i < 0)
						break;
					output.append(new String(tmp, 0, i) + "\n");
				}

				if (channel.isClosed()) {
					System.out.println("exit-status: " + channel.getExitStatus());
					break;
				}
				Thread.sleep(1000);
			}
			GenericReply reply = GenericReply.newBuilder().setOutput(output.toString()).build();
			responseObserver.onNext(reply);
			responseObserver.onCompleted();
			channel.disconnect();
			session.disconnect();
			outputFile.delete();
		} catch (JSchException e) {
			logger.log(Level.WARNING, "JSch failed: {0}", e.getMessage());
		} catch (IOException e) {
			logger.log(Level.WARNING, "IO Exception: {0}", e.getMessage());
		} catch (InterruptedException e) {
			e.printStackTrace();
		} 
	}

	
}
