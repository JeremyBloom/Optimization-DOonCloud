/**
 * 
 */
package com.ibm.optim.oaas.sample.optimization;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.spark.sql.types.StructType;

import com.ibm.optim.oaas.client.OperationException;
import com.ibm.optim.oaas.client.job.AttachmentContentWriter;
import com.ibm.optim.oaas.client.job.AttachmentNotFoundException;
import com.ibm.optim.oaas.client.job.JobClient;
import com.ibm.optim.oaas.client.job.JobClientFactory;
import com.ibm.optim.oaas.client.job.JobException;
import com.ibm.optim.oaas.client.job.JobExecutor;
import com.ibm.optim.oaas.client.job.JobExecutorFactory;
import com.ibm.optim.oaas.client.job.JobInput;
import com.ibm.optim.oaas.client.job.JobNotFoundException;
import com.ibm.optim.oaas.client.job.JobOutput;
import com.ibm.optim.oaas.client.job.JobRequest;
import com.ibm.optim.oaas.client.job.JobRequestBuilder;
import com.ibm.optim.oaas.client.job.JobResponse;
import com.ibm.optim.oaas.client.job.SubscriptionException;
import com.ibm.optim.oaas.client.job.model.JobSolveStatus;

import com.ibm.optim.sample.oplinterface.OPLCollector;
import com.ibm.optim.sample.oplinterface.OPLGlobal;

/**
 * Handles the actual optimization task.
 * Creates and executes a job builder for an optimization problem instance.
 * Encapsulates the DOCloud API.
 * This class is designed to facilitate multiple calls to the optimizer, such as would occur in a decomposition algorithm,
 * although it transparently supports single use as well.
 * In particular, the data can be factored into a constant data set that does not vary from run to run (represented by a JSON or .dat file)
 * and a variable piece that does vary (represented by a Collector object).
 * The optimization model can also be factored into two pieces, a best practice for large models and multi-models:
 * A data model that defines the tuples and tuple sets that will contain the input and output data.
 * An optimization model that defines the decision variables, decision expressions, objective function, 
 * constraints, and pre- and post-processing data transformations.
 * Factoring either the data or the optimization model in this fashion is optional.
 * 
 * The problem instance is specified by the OPL model and input data received from the invoking (e.g. ColumnGeneration) instance.
 * Input and output data are realized as instances of OPLCollector, which in turn are specified by their respective schemas.
 * This class is completely independent of the specific optimization problem to be solved.
 * 
 * @author bloomj
 *
 */
public class Optimizer {

	public static Logger LOG= Logger.getLogger(Optimizer.class.getName()); 
	
//	Optimization Model and Data Objects
	protected Optimizer.ModelSource model; 
	protected Map<String, StructType> resultDataModel;
	protected Map<String, URL> attachments;
	protected List<InputStream> streamsRegistry; //collects streams created so that they can be closed when the solve job has completed
	
//	Job Management objects
	protected JobExecutor executor;
	protected JobClient jobclient;
	protected JobRequestBuilder builder;
	protected JobSolveStatus solveStatus;
	
	protected List<OPLCollector> history;
	
	protected String name;

	/**
	 * Constructs an Optimizer instance.
	 * The instance requires an optimization model as a parameter.
	 * 
	 * You can also provide one or more data files as attachments, either in OPL .dat or in JSON format. This data does not
	 * change from solve to solve. If you have input data that does change, you can provide it to the solve method as an OPLCollector object.
	 * 
	 * @param problemName name of this optimization problem instance
	 * @param model an optimization model written in OPL
	 * @param resultDataModel the application data model for the results of the optimization
	 * @param attachments URLs for files representing the data that does not vary from solve to solve
	 */
	protected Optimizer(String problemName, Optimizer.ModelSource model, Map<String, StructType> resultDataModel, Collection<URL> attachments) {
		super();
		this.name= problemName;
		this.model= model;
		this.resultDataModel= resultDataModel;
		this.attachments= new LinkedHashMap<String, URL>();
		if(attachments!=null)
			for(URL f: attachments)
				this.attachments.put(getFileName(f), f);
		this.streamsRegistry= new ArrayList<InputStream>();
		this.history= new ArrayList<OPLCollector>();
		
		URL propertyFileURL = Optimizer.class.getResource("config.properties");
		if (propertyFileURL == null) {
			throw new RuntimeException("Properties file not found");
		}
		Properties properties = Optimizer.getProperties(propertyFileURL);
		this.jobclient= JobClientFactory.createDefault(properties.getProperty("api.url"), properties.getProperty("authentication.token"));
		this.executor= JobExecutorFactory.createDefault();
		this.solveStatus= JobSolveStatus.UNKNOWN;
	}
	
	/**
	 * Creates an Optimizer instance with null or empty parameters.
	 * Set the parameters using the various setter methods provided.
	 * 
	 * @param problemName name of this optimization problem instance
	 */
	public Optimizer(String problemName) {
		this(problemName, null, null, null);
	}
	
	/**
	 * Sets the OPL model as URLs pointing to files.
	 * This method can take any number of URL arguments, but
	 * there are two common use cases:
	 * First, the optimization model can be composed of two pieces: 
	 * A data model that defines the tuples and tuple sets that will contain the input and output data.
	 * An optimization model that defines the decision variables, decision expressions, objective function, 
	 * constraints, and pre- and post-processing data transformations.
	 * The two are concatenated, so they must be presented in that order.
	 * If such a composite model is used, you do not need to import the data model into the optimization model using an OPL include statement.
	 * Second, you do not have to use a separate data model, in which case a single model URL must be provided 
	 * which encompasses both the data model and the optimization model.
	 * 
	 * @param name the name assigned to this OPL model (should have the format of a file name with a .mod extension)
	 * @param dotMods URLs of the .mod files
	 * 
	 * @return this optimizer
	 * @throws IllegalArgumentException if a model has already been defined
	 */
	public Optimizer setOPLModel(String name, URL... dotMods) {
		if (this.model != null)
			throw new IllegalArgumentException("model has already been defined");
		this.model= new Optimizer.ModelSource(name, dotMods);
		return this;
	}
	
	/**
	 * Sets the OPL model as a set of strings.
	 * The strings are concatenated, and must specify both a data model and an optimization model in correct OPL grammar.
	 * 
	 * @param name the name assigned to this OPL model (should have the format of a file name with a .mod extension)
	 * @param modelText the text of the OPL model
	 * @return this optimizer
	 * @throws IllegalArgumentException if a model has already been defined as either an attachment or as a composite model
	 */
	public Optimizer setOPLModel(String name, String... modelText) {
		if (this.model != null)
			throw new IllegalArgumentException("model has already been defined");
		this.model= new Optimizer.ModelSource(name, modelText);
		return this;
	}
	
	/**
	 * Sets the application data model for the results of the optimization 
	 * 
	 * @param resultDataModel the application data model for the results of the optimization
	 * @return this optimizer
	 * @throws IllegalArgumentException if a results data model has already been defined
	 */
	public Optimizer setResultDataModel(Map<String, StructType> resultDataModel) {
		if (this.resultDataModel != null)
			throw new IllegalArgumentException("results data model has already been defined");		
		this.resultDataModel = resultDataModel;
		return this;
	}
	
	/**
	 * Attaches one or more data files, either in OPL .dat or in JSON format. This data does not
	 * change from solve to solve. If you have input data that does change, you can provide it as a Collector object.
	 * 
	 * @param attachments URLs for files representing the data that does not vary from solve to solve
	 * @return this optimizer
	 * @throws IllegalArgumentException if an item of the same name has already been attached
	 */
	public Optimizer attachData(Collection<URL> attachments) {
		URL oldValue;
		for(URL f: attachments) {
			oldValue= this.attachments.put(getFileName(f), f);
			if (oldValue != null) //checks to see if this URL has already been attached
				throw new IllegalArgumentException(getFileName(f) + "already attached");
		}
		return this;
	}
	
	public Optimizer attachData(URL... attachments) {
		return this.attachData(Arrays.asList(attachments));
	}

	/**
	 * Solves an optimization problem instance by calling the DOCloud solve service (Oaas).
	 * Creates a new job request, incorporating any changes to the variable input data, 
	 * for a problem instance to be processed by the solve service. 
	 * Once the problem is solved, the results are mapped to an instance of an OPL Collector.
	 * Note: this method will set a new destination for the JSON serialization of the input data.
	 * 
	 * @param inputData the variable, solve-specific input data (null if not used)
	 * @param solutionId an identifier for the solution, used in iterative algorithms (set to empty string if not needed)
	 * @return a solution collector
	 */
	public OPLCollector solve(OPLCollector inputData, String solutionId) {
		JobRequest request;
		InputStream stream;
		String jobid= "";
		Future<JobResponse>submit= null;
		JobResponse response= null;
		SolverInput data= null;
		SolverOutput results= new SolverOutput(streamsRegistry);
		OPLCollector solution= null;
		this.solveStatus= JobSolveStatus.UNKNOWN;		

		builder= jobclient.newRequest()
				.log(new File("results.log"))
				.output(results)
//				.deleteOnCompletion(true)
				.livelog(System.out)
				.timeout(5, TimeUnit.MINUTES);

		if(this.model == null)
			throw new IllegalArgumentException("A model attachment must be provided to the optimizer");
		else if(!this.model.isEmpty()) {
			stream= this.model.toStream();
			builder= builder.input(this.model.getName(), stream);
			this.streamsRegistry.add(stream);
		}

		if(this.attachments != null && this.attachments.size()>0) {
			for(String f: this.attachments.keySet()) {
				stream= getStream(attachments.get(f));
				builder= builder.input(f, stream);
				this.streamsRegistry.add(stream);
			}
		}

		if(inputData != null) {
			data= new SolverInput(inputData.getName()+".json", inputData);
			builder= builder.input(data);
		}

		try {
			request= builder.build();
//			OPLGlobal.out.print("In Optimizer.solve 0");
			submit=	request.execute(executor);
			jobid= request.getJobId();
//			OPLGlobal.out.println(" jobid: " + jobid);
			response= submit.get();
//			OPLGlobal.out.println("In Optimizer.solve 1: is done? "+ submit.isDone() + " status= " + response.getJob().getSolveStatus());

			this.solveStatus= response.getJob().getSolveStatus(); //INFEASIBLE_SOLUTION or UNBOUNDED_SOLUTION or OPTIMAL_SOLUTION or...
//			OPLGlobal.out.println("In Optimizer.solve 1: execution status= " + response.getJob().getExecutionStatus());
//			OPLGlobal.out.println();
			
			switch (response.getJob().getExecutionStatus()) {
			case PROCESSED:
				results.download(jobclient, jobid);
				
/*				BufferedReader br= new BufferedReader(new InputStreamReader(results.getStream()));
				String line= br.readLine();
				while(line != null) {
					OPLGlobal.out.println(line);
					line= br.readLine();
				}
*/				
				solution= (new OPLCollector(this.getName()+"Result"+solutionId, resultDataModel))
						.setJsonSource(results.getStream())
						.fromJSON();
//				solution.fromJSON();
				history.add(solution);
				break;
			case FAILED:
				// get failure message if defined
				String message= "";
				if (response.getJob().getFailureInfo() != null) {
					message= response.getJob().getFailureInfo().getMessage();
				}
				LOG.info("Failed " +message);
				break;
			default:
				break;
			}/*switch*/

		} 
		catch (OperationException | InterruptedException | ExecutionException
				| IOException | JobException e) {
			LOG.log(Level.WARNING, "Error while executing job",e);
		}
		finally {
			try {
//				System.out.println("In Optimizer.solve 3: deleting job " + jobid);
				for(InputStream s: streamsRegistry)
					s.close();
				jobclient.deleteJob(jobid);
				System.out.println("In Optimizer.solve 4: job deleted");
				
			} catch (OperationException | IOException e) {
				System.err.println("job " + jobid + " could not be deleted");
				e.printStackTrace();
			}				
		}
		
		return solution;
	}/*solve*/

	/**
	 * Use this method when an identifier for the solution is not needed.
	 *  
	 * @param inputData the variable, solve-specific input data (null if not used)
	 * @return a solution collector
	 */
	public OPLCollector solve(OPLCollector inputData) {
		return solve(inputData, "");

	}
	
	/**
	 * Use this method when the only input data are files attached in the optimizer. 
	 * 
	 * @return a solution collector
	 */
	public OPLCollector solve() {
		return solve(null, "");
	}
	
	/**
	 * Gets information about the current solve status of the job.
	 * 
	 * @return enum JobSolveStatus (UNKNOWN if the solve has not completed)
	 */
	public JobSolveStatus getSolveStatus() {
		return this.solveStatus;
	}
	
	public String getName() {
		return name;
	}

	/**
	 * Get properties specified in <i>config.properties</i> configuration file.
	 * 
	 * @param propertyFileURL
	 *          URL of the <i>config.properties</i> configuration file.
	 * @return Properties loaded from the <i>config.properties</i>
	 */
	public static Properties getProperties(URL propertyFileURL) {
		Properties properties = new Properties();
		try {
			properties.load(propertyFileURL.openStream());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return properties;
	}
	
	/**
	 * Gets the filename and extension from a URL.
	 * 
	 * @param url the URL to be parsed
	 * @return a string of the form filename.ext
	 */
	public static String getFileName(URL url) {
		String path= url.getPath();
		return path.substring(path.lastIndexOf('/')+1, path.length());
	}
	
	/**
	 * Opens a connection to url and returns an InputStream for reading from that connection. 
	 * 
	 * @param url the URL to be read
	 * @return an input stream
	 * @throws IOException if the stream cannot be opened
	 */
	public static InputStream getStream(URL url) {
		InputStream result= null;
		try {
			result= url.openStream();
		} catch (IOException e) {
			System.err.println("Bad URL" + getFileName(url));
			e.printStackTrace();
		}
		return result;
	}

	/**
	 * Gets the location of a file in a resource folder.
	 * 
	 * @param source the class associated with the resource folder 
	 * @param file the file name within the resource folder
	 * @return URL of the resource
	 */
	public static URL getFileLocation(Class<?> source, String file) {
		URL url = source.getResource(file);
		if (url == null)
			throw new RuntimeException(file +" not found");
		return url;		
	}
	
	/**
	 * Gets the locations of a collection of files in a resource folder.
	 * 
	 * @param source the class associated with the resource folder 
	 * @param files the file names within the resource folder
	 * @return URLs of the resources
	 */
	public static List<URL> getFileLocations(Class<?> source, Collection<String> files) {
		List<URL> result= new ArrayList<URL>();
		for(String f: files)
			result.add(getFileLocation(source, f));
		return result;
	}
	
	public static URL[] getFileLocations(Class<?> source, String... files) {
		return 	getFileLocations(source, Arrays.asList(files)).toArray(new URL[0]);
	}
	
	public void shutdown(){
		executor.shutdown();
	}
	
	public List<OPLCollector> getHistory() {
		return Collections.unmodifiableList(this.history);
	}
	
	public void clearHistory() {
		history.clear();
	}

	/**
	 * This class manages the OPL source code for an optimization model.
	 * It can use an OPL model specified by one or more files, indicated by their URLs, or
	 * it can use an OPL model specified by one or more Strings. 
	 * Use of one OPL component is the norm, but this class also
	 * enables factoring an OPL model into a data model and an optimization model.
	 * Using such a two-piece factorization is a best practice for large models and multi-models:
	 * The data model defines the tuples and tuple sets that will contain the input and output data.
	 * The optimization model defines the decision variables, decision expressions, objective function, 
	 * constraints, and pre- and post-processing data transformations.
	 * 
	 * When the OPL model consists of multiple components, ModelSource concatenates them in the order
	 * presented, and it is not necessary to use OPL include statements to import the components.
	 * The multiple model files need not be located in the same resource folder.
	 * 
	 * Note: developers generally need not use this class directly. Instead, it is recommended
	 * to use the setOPLModel method of the Optimizer class.
	 * 
	 * @author bloomj
	 *
	 */
	public static class ModelSource {
		
		private String name;
		private URL[] dotMods;
		private String[] modelText;
		
		/**
		 * Creates a new ModelSource instance from URLs pointing to OPL .mod files.
		 * This method can take any number of URL arguments, but
		 * there are two common use cases:
		 * First, the optimization model can be composed of two pieces: 
		 * A data model that defines the tuples and tuple sets that will contain the input and output data.
		 * An optimization model that defines the decision variables, decision expressions, objective function, 
		 * constraints, and pre- and post-processing data transformations.
		 * The two are concatenated, so they must be presented in that order.
		 * If such a composite model is used, you do not need to import the data model into the optimization model using an OPL include statement.
		 * Second, you do not have to use a separate data model, in which case a single model URL must be provided 
		 * which encompasses both the data model and the optimization model.
		 * 
		 * @param name the name assigned to this OPL model (should have the format of a file name with a .mod extension)
		 * @param dotMods URLs of the .mod files
		 */
		public ModelSource(String name, URL... dotMods) {
			if(dotMods.length==0)
				throw new IllegalArgumentException("argument cannot be empty");
			this.name=  name;
			this.dotMods= dotMods;
			this.modelText= null;
		}
		
		/**
		 * Creates a new ModelSource instance from an OPL model in a set of strings.
		 * The strings are concatenated, and must specify both a data model and an optimization model in correct OPL grammar.
		 * 
		 * @param name the name assigned to this OPL model (should have the format of a file name with a .mod extension)
		 * @param modelText the text of the OPL model
		 */
		public ModelSource(String name, String... modelText) {
			if(modelText.length==0)
				throw new IllegalArgumentException("argument cannot be empty");
			this.name=  name;
			this.dotMods= null;
			this.modelText= modelText;		
		}

		/**
		 * @return the name assigned to this OPL model
		 */
		public String getName() {
			return name;
		}
		
		/**
		 * @return true if both dotMods and modelText are null; false otherwise
		 */
		public boolean isEmpty() {
			return dotMods==null && modelText==null;
		}
		
		/**
		 * Concatenates the model components and creates an input stream for reading them.
		 * 
		 * @return an InputStream instance
		 */
		public InputStream toStream() {
			InputStream result= null;
			if(dotMods!=null) {
				if(dotMods.length==1) {
					result= getStream(dotMods[0]);
					return result;
				}
				else { //It is necessary to create SequenceInputStream with Enumeration argument so that closing the stream closes its components as well
					Iterator<URL> i= Arrays.asList(dotMods).iterator();
					result= new SequenceInputStream(new Enumeration<InputStream>() {
						@Override
						public boolean hasMoreElements() {
							return i.hasNext();
						}
						
						@Override
						public InputStream nextElement() {
							return getStream(i.next());
						}
					}/*end Enumeration*/);
					return result;
				}/*else*/
			}/*if*/
			else if(modelText!=null) {
				StringBuilder t= new StringBuilder();
				t.append(modelText[0]);
				t.append('\n');
				for(int i=1; i<modelText.length; i++) {
					t.append(modelText[i]);
					t.append('\n');
				}
				result= new ByteArrayInputStream(t.toString().getBytes(Charset.defaultCharset())); 
				return result;			
			}
			else
				throw new IllegalStateException("model source is empty");
		}
				
	}/*class ModelSource*/
	
	/**
	 * This class permits streaming data from Spark to the solving service.
	 * As input data could be large, this implementation shows how to stream the data from an OPLCollector to DOcplexcloud 
	 * without rebuilding the data in memory.
	 * 
	 * Objects of this class are created an maintained by the Optimizer instance, and normally,
	 * an application developer need not use it.
	 * 
	 * See the https://github.com/IBMDecisionOptimization/DOcloud-GreenTruck-sample for details, particularly
	 * https://github.com/IBMDecisionOptimization/DOcloud-GreenTruck-sample/blob/master/src/main/java/com/ibm/optim/oaas/sample/trucking/ejb/impl/TruckingJobInput.java
	 * 
	 * @author bloomj
	 *
	 */
	static class SolverInput implements JobInput {
		
		private String name;
		private OPLCollector inputData;
		
		/**
		 * Constructs a new SolverInput instance.
		 * 
		 * @param name the name of attachment file generated; should generally have a .json extension 
		 * @param inputData the input collector, set of Spark datsets
		 */
		public SolverInput(String name, OPLCollector inputData) {
			super();
			this.name= name;
			this.inputData= inputData;
		}

		/**
		 * Returns -1 because the length of the input source is unknown.
		 * @see com.ibm.optim.oaas.client.job.JobInput#getLength()
		 */
		@Override
		public long getLength() {
			return -1;
		}

		/**
		 * Returns the name of the input source. The name will be used to declare the attachment.
		 * @see com.ibm.optim.oaas.client.job.JobInput#getName()
		 */
		@Override
		public String getName() {
			return name;
		}

		/**
		 * Returns false because the upload cannot be repeated.
		 * @see com.ibm.optim.oaas.client.job.JobInput#isRepeatable()
		 */
		@Override
		public boolean isRepeatable() {
			return false;
		}

		/**
		 * Uploads this source as the contents of an attachment to a given job. The attachment was declared by the executor with the name returned by the job input.
		 * Note: this method connects the output stream from the client to the input stream of the solving service.
		 * This output stream is used as the JSON destination to serialize the input data collector.
		 * @see com.ibm.optim.oaas.client.job.JobInput#upload(com.ibm.optim.oaas.client.job.JobClient, java.lang.String)
		 */
		@Override
		public void upload(JobClient client, String jobid) throws OperationException, IOException, JobNotFoundException,
				AttachmentNotFoundException, SubscriptionException {
//			System.out.println("In Optimizer.SolverInput 0: jobid= " + jobid);

			client.uploadJobAttachment(jobid, getName(),
					new AttachmentContentWriter() {

						@Override
						public boolean isRepeatable() {
							return SolverInput.this.isRepeatable();
						}
		
						@Override
						public void writeTo(OutputStream outstream) throws IOException {
//							System.out.println("In Optimizer.SolverInput 1: ");
//							System.out.println(inputData.getTableNames().toString());
							inputData.setJsonDestination(outstream).toJSON();
						}
					}/*local class AttachmentContentWriter*/
			
			)/*uploadJobAttachment method*/;
			
		}/*upload method*/
		
	}/*class SolverInput*/
	
	/**
	 * This class permits streaming data from the solving service to Spark.
	 * As output data could be large, this implementation shows how to access and filter the
	 * results from DOcloud and store them in Spark without rebuilding the data in memory.
	 * Objects of this class are created an maintained by the Optimizer instance, and normally,
	 * an application developer need not use it.
	 * 
	 * See the https://github.com/IBMDecisionOptimization/DOcloud-GreenTruck-sample for details, particularly
	 * https://github.com/IBMDecisionOptimization/DOcloud-GreenTruck-sample/blob/master/src/main/java/com/ibm/optim/oaas/sample/trucking/ejb/impl/TruckingJobOutput.java
	 * 
	 * @author bloomj
	 * 
	 */
	static class SolverOutput implements JobOutput {
		
		private String name;
		private InputStream stream;
		private List<InputStream> streamsRegistry;

		/**
		 * Creates a new SolverOutput instance.
		 * @param streamsRegistry stores the stream created in order to close it when the solve job is finished
		 */
		private  SolverOutput(List<InputStream> streamsRegistry) {
			super();
			this.stream= null;
			this.streamsRegistry= streamsRegistry;
		}

		/**
		 * Downloads a job attachment.
		 * Note: Creates an input stream to the client that connects to an output stream from the solving service.
		 * This stream is used as the JSON source to deserialize the results data into 
		 * an OPLCollector, a set of Spark datasets containing the solution.
		 * @see com.ibm.optim.oaas.client.job.JobOutput#download(com.ibm.optim.oaas.client.job.JobClient, java.lang.String)
		 */
		@Override
		public void download(JobClient client, String jobid)
				throws JobNotFoundException, AttachmentNotFoundException, OperationException, IOException {
			this.stream= client.downloadJobAttachment(jobid, getName());
			streamsRegistry.add(this.stream);
		}

		/**
		 * Returns the content of the attachment as an input stream.
		 * @see com.ibm.optim.oaas.client.job.JobOutput#getContent()
		 */
		@Override
		public Object getContent() {
			return this.stream;
		}

		/**
		 * Returns the name of the output source.
		 * @see com.ibm.optim.oaas.client.job.JobOutput#getName()
		 */
		@Override
		public String getName() {
			return this.name;
		}

		/**
		 * Sets the name of the output. This method is called by the executor when the output name is retrieved.
		 * @see com.ibm.optim.oaas.client.job.JobOutput#setName(java.lang.String)
		 */
		@Override
		public void setName(String name) {
			this.name= name;		
		}
		
		/**
		 * Returns the content of the attachment as an input stream.
		 */
		public InputStream getStream() {
			return this.stream;
		}
		
	}/*class SolverOutput*/
	

}/*class Optimizer*/
