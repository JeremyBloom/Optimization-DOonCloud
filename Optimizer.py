__author__ = 'bloomj'

'''
Created on Feb 9, 2017
@author: bloomj
'''
try:
    import docloud
except:
    if hasattr(sys, 'real_prefix'):
        # we are in a virtual env.
        !pip install docloud
    else:
        !pip install - -user docloud

from docloud.job import JobClient
from docloud.status import JobSolveStatus, JobExecutionStatus

from urlparse import urlparse

import fileinput
import urllib
import cStringIO
from pprint import pprint


class Optimizer(object):
    '''
     Handles the actual optimization task.
     Creates and executes a job builder for an optimization problem instance.
     Encapsulates the DOCloud API.
     This class is designed to facilitate multiple calls to the optimizer, such as would occur in a decomposition algorithm,
     although it transparently supports single use as well.
     In particular, the data can be factored into a constant data set that does not vary from run to run (represented by a JSON or .dat file)
     and a variable piece that does vary (represented by a Collector object).
     The optimization model can also be factored into two pieces, a best practice for large models and multi-models:
     A data model that defines the tuples and tuple sets that will contain the input and output data.
     An optimization model that defines the decision variables, decision expressions, objective function,
     constraints, and pre- and post-processing data transformations.
     Factoring either the data or the optimization model in this fashion is optional.

     The problem instance is specified by the OPL model and input data received from the invoking (e.g. ColumnGeneration) instance.
     Input and output data are realized as instances of OPLCollector, which in turn are specified by their respective schemas.
     This class is completely independent of the specific optimization problem to be solved.
    '''

    def __init__(self, problemName, model=None, resultDataModel=None, credentials=None, *attachments):
        '''
         Constructs an Optimizer instance.
         The instance requires an optimization model as a parameter.
         You can also provide one or more data files as attachments, either in OPL .dat or in JSON format. This data does not
         change from solve to solve. If you have input data that does change, you can provide it to the solve method as an OPLCollector object.
         :param problemName: name of this optimization problem instance
         :type problemName: String
         :param model: an optimization model written in OPL
         :type model: Model.Source object or String
         :param resultDataModel: the application data model for the results of the optimization
         :type resultDataModel: dict<String, StructType>
         :param credentials: DOcplexcloud url and api key
         :type credentials: {"url":String, "key":String}
         :param attachments: URLs for files representing the data that does not vary from solve to solve
         :type attachments: list<URL>
        '''
        self.name = problemName
        self.model = model
        self.resultDataModel = resultDataModel
        self.attachData(attachments)
        self.streamsRegistry = []
        self.history = []

        self.credentials = credentials

        self.jobclient = JobClient(credentials["url"], credentials["key"]);
        self.solveStatus = JobSolveStatus.UNKNOWN;

    def getName(self):
        """
        Returns the name of this problem
        """
        return self.name

    def setOPLModel(self, name, dotMods=None, modelText=None):
        '''
         Sets the OPL model.
         This method can take any number of dotMod arguments, but
         there are two common use cases:
         First, the optimization model can be composed of two pieces:
             A data model that defines the tuples and tuple sets that will contain the input and output data.
             An optimization model that defines the decision variables, decision expressions, objective function,
             constraints, and pre- and post-processing data transformations.
             The two are concatenated, so they must be presented in that order.
             If such a composite model is used, you do not need to import the data model into the optimization model using an OPL include statement.
         Second, you do not have to use a separate data model, in which case a single dotMod must be provided
         which encompasses both the data model and the optimization model.
        @param name: the name assigned to this OPL model (should have the format of a file name with a .mod extension)
        @type name: String
        @param dotMods: URLs pointing to OPL .mod files, which will be concatenated in the order given
        @type dotMods: List<URL>
        @param modelText: the text of the OPL model, which will be concatenated in the order given
        @type modelText: List<String>
        @return this optimizer
        @raise ValueError if a model has already been defined or if dotMods or modelText is empty
        '''
        if self.model is not None:
            raise ValueError("model has already been set")
        self.model = ModelSource(name=name, dotMods=dotMods, modelText=modelText)
        return self

    def setResultDataModel(self, resultDataModel):
        '''
        Sets the application data model for the results of the optimization
        @param resultDataModel: the application data model for the results of the optimization
        @type resultDataModel: dict<String, StructType>
        '''
        if self.resultDataModel is not None:
            raise ValueError("results data model has already been defined")
        self.resultDataModel = resultDataModel
        return self

    def attachData(self, attachments):
        '''
        Attaches one or more data files, either in OPL .dat or in JSON format. This data does not
        change from solve to solve. If you have input data that does change, you can provide it as a Collector object.
        @param attachments: files representing the data that does not vary from solve to solve
        @type attachments: list<URL>
        @return this optimizer
        @raise ValueError if an item of the same name has already been attached
        '''
        self.attachments = {}
        if attachments is not None:
            for f in attachments:
                fileName = os.path.splitext(os.path.basename(urlparse(f)))[0]
                if fileName in self.attachments:
                    raise ValueError(fileName + " already attached")
                self.attachments[fileName] = f
        return self;

    def solve(self, inputData=None, solutionId=""):
        '''
        Solves an optimization problem instance by calling the DOCloud solve service (Oaas).
        Creates a new job request, incorporating any changes to the variable input data,
        for a problem instance to be processed by the solve service.
        Once the problem is solved, the results are mapped to an instance of an OPL Collector.
        Note: this method will set a new destination for the JSON serialization of the input data.
        @param inputData: the variable, solve-specific input data
        @type inputData: OPLCollector
        @param solutionId: an identifier for the solution, used in iterative algorithms (set to empty string if not needed)
        @type solutionId: String
        @return: a solution collector
        '''
        inputs = []
        if self.model is None:
            raise ValueError("A model attachment must be provided to the optimizer")
        if self.model:  # is not empty
            stream = self.model.toStream()
            inputs.append({"name": self.model.getName(), "file": stream})
            self.streamsRegistry.append(stream)
        if self.attachments:  # is not empty
            for f in self.attachments:
                stream = urllib.FancyURLopener(self.attachments[f])
                inputs.append({"name": f, "file": stream})
                self.streamsRegistry.append(stream)
        if inputData is not None:
            outStream = cStringIO.StringIO()
            inputData.setJsonDestination(outStream).toJSON()
            inStream = cStringIO.StringIO(outStream.getvalue())
            inputs.append({"name": inputData.getName() + ".json", "file": inStream})
            self.streamsRegistry.extend([outStream, inStream])

        response = self.jobclient.execute(
            input=inputs,
            output="results.json",
            load_solution=True,
            log="solver.log",
            gzip=True,
            waittime=300,  # seconds
            delete_on_completion=False)

        self.jobid = response.jobid

        status = self.jobclient.get_execution_status(self.jobid)
        if status == JobExecutionStatus.PROCESSED:
            results = cStringIO.StringIO(response.solution)
            self.streamsRegistry.append(results)
            self.solveStatus = response.job_info.get(
                'solveStatus')  # INFEASIBLE_SOLUTION or UNBOUNDED_SOLUTION or OPTIMAL_SOLUTION or...
            solution = (OPLCollector(self.getName() + "Result" + solutionId, self.resultDataModel)).setJsonSource(
                results).fromJSON()
            self.history.append(solution)
        elif status == JobExecutionStatus.FAILED:
            # get failure message if defined
            message = ""
            if (response.getJob().getFailureInfo() != None):
                message = response.getJob().getFailureInfo().getMessage()
            print("Failed " + message)
        else:
            print("Job Status: " + status)

        for s in self.streamsRegistry:
            s.close();
        self.jobclient.delete_job(self.jobid);

        return solution

    def getSolveStatus(self):
        """
        @return the solve status as a string
        Attributes:
            UNKNOWN: The algorithm has no information about the solution.
            FEASIBLE_SOLUTION: The algorithm found a feasible solution.
            OPTIMAL_SOLUTION: The algorithm found an optimal solution.
            INFEASIBLE_SOLUTION: The algorithm proved that the model is infeasible.
            UNBOUNDED_SOLUTION: The algorithm proved the model unbounded.
            INFEASIBLE_OR_UNBOUNDED_SOLUTION: The model is infeasible or unbounded.
        """
        return self.solveStatus


# end class Optimizer

class ModelSource(object):
    '''
     This class manages the OPL source code for an optimization model.
     It can use an OPL model specified by one or more files, indicated by their URLs, or
     it can use an OPL model specified by one or more Strings.
     Use of one OPL component is the norm, but this class also
     enables factoring an OPL model into a data model and an optimization model.
     Using such a two-piece factorization is a best practice for large models and multi-models:
     The data model defines the tuples and tuple sets that will contain the input and output data.
     The optimization model defines the decision variables, decision expressions, objective function,
     constraints, and pre- and post-processing data transformations.

     When the OPL model consists of multiple components, ModelSource concatenates them in the order
     presented, and it is not necessary to use OPL include statements to import the components.
     The multiple model files need not be located in the same resource folder.

     Note: developers generally need not use this class directly. Instead, it is recommended
     to use the setOPLModel method of the Optimizer class.
    '''

    def __init__(self, name="OPL.mod", dotMods=None, modelText=None):
        '''
         Creates a new ModelSource instance from URLs pointing to OPL .mod files.
         This method can take any number of URL arguments, but
         there are two common use cases:
         First, the optimization model can be composed of two pieces:
         A data model that defines the tuples and tuple sets that will contain the input and output data.
         An optimization model that defines the decision variables, decision expressions, objective function,
         constraints, and pre- and post-processing data transformations.
         The two are concatenated, so they must be presented in that order.
         If such a composite model is used, you do not need to import the data model into the optimization model using an OPL include statement.
         Second, you do not have to use a separate data model, in which case a single model URL must be provided
         which encompasses both the data model and the optimization model.

        @param name: the name assigned to this OPL model (should have the format of a file name with a .mod extension)
        @type name: String
        @param dotMods: URLs pointing to OPL .mod files, which will be concatenated in the order given
        @type dotMods: List<URL>
        @param modelText: the text of the OPL model, which will be concatenated in the order given
        @type modelText: List<String>
        @raise: ValueError if dotMods or modelText is empty
        '''

        self.name = name;
        if dotMods is not None and not dotMods:  # is empty
            raise ValueError("argument cannot be empty");
        self.dotMods = dotMods;
        if modelText is not None and not modelText:  # is empty
            raise ValueError("argument cannot be empty");
        self.modelText = modelText;

    def getName(self):
        '''
         @return:  the name assigned to this OPL model
         @type String
        '''
        return self.name

    def isEmpty(self):
        '''
         @return true if both dotMods and modelText are null; false otherwise
        '''
        return self.dotMods is None and self.modelText is None

    def toStream(self):
        '''
         Concatenates the model components and creates an input file for reading them.

         @return a file
        '''
        if self.dotMods:  # is not empty
            result = fileinput.input((urllib.FancyURLopener(f) for f in self.dotMods))
            return result
        if self.modelText:  # is not empty
            result = cStringIO.StringIO("".join(self.modelText))
            return result
        raise ValueError("model source is empty")

    # end class ModelSource