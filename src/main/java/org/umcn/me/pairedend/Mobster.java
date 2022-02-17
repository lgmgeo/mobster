package org.umcn.me.pairedend;

import java.io.*;
import java.util.Properties;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.umcn.me.samexternal.SAMDefinitions;
import org.umcn.me.util.MobileDefinitions;

public final class Mobster {

    public static Logger logger = Logger.getLogger("Mobster");
    public static String propertiesFile = null;
    public static String inFile = null;
    public static String outFile = null;
    public static String sampleName = null;
    public static boolean vcfOut = false;
    public static final String VERSION;
    public static final Properties defaultProperties;

    public static int EXTERNAL_DEPENDENT_PROGRAM_FAIL_EXIT_CODE = -1;
    public static int PROPERTIES_FILE_NOT_FOUND_EXIT_CODE = 1;
    public static int COULD_NOT_READ_PROPERTIES_FILE_EXIT_CODE = 2;

    static {

        //Load version properties
        Properties prop = new Properties();
        try {
            prop.load(Mobster.class.getResourceAsStream("/properties/version.properties"));
        } catch (FileNotFoundException e) {
            logger.error("Could not load version properties", e);
            System.exit(PROPERTIES_FILE_NOT_FOUND_EXIT_CODE);
        } catch (IOException e) {
            logger.error("Could not read version properties", e);
            System.exit(COULD_NOT_READ_PROPERTIES_FILE_EXIT_CODE);
        }
        VERSION = prop.getProperty("version");

        //Load default mobster properties
        defaultProperties = new Properties();
        try {
            prop.load(Mobster.class.getResourceAsStream("/properties/version.properties"));
        } catch (FileNotFoundException e) {
            logger.error("Could not load Mobster properties", e);
            System.exit(PROPERTIES_FILE_NOT_FOUND_EXIT_CODE);
        } catch (IOException e) {
            logger.error("Could not read Mobster properties", e);
            System.exit(COULD_NOT_READ_PROPERTIES_FILE_EXIT_CODE);
        }

    }

    public static void main(String[] args) {

        //Setup configurator
        BasicConfigurator.configure();

        //Copy the default parameters into a local variable
        Properties props = (Properties) defaultProperties.clone();

        //Parse the arguments and override properties inside props
        parseArgs(args, props);

        try {

            //Run picard if enabled
            if (props.containsKey(MobileDefinitions.PICARD_COLLECT_INSERT_METRICS) &&
                    "true".equals(props.getProperty(MobileDefinitions.USE_PICARD).trim())) {
                configureAndRunPicard(props);
            }

            //Extract potential supporting reads
            extractPotentialMEIReads(props);

            //Map them to the mobiome
            final int exitStatus = runMobiomeMapping(props);
            if (exitStatus != 0){
                logger.fatal("Mobiome mapping finished with non-normal exit value: " + exitStatus);
                logger.fatal("Therefore Mobster execution is terminated");
                System.exit(EXTERNAL_DEPENDENT_PROGRAM_FAIL_EXIT_CODE);
            }

            //???
            runMEIPairFinder(props);

            //Cluster the supporting reads to find the MEIs and write them to output files
            AnchorClusterer.runFromPropertiesFile(props);

        } catch (IOException e) {
            logger.error(e.getMessage());
            System.exit(-1);
        }
    }

    // note that this is conditional, i.e. only if user requires this
    private static void configureAndRunPicard(final Properties props) throws IOException {
        //NOTE if multiple BAM Files are provided, only the insert size is investigated of the 1st BAM
        String picardCommand = "java -Xmx4g -jar " + props.getProperty(MobileDefinitions.PICARD_COLLECT_INSERT_METRICS) +
                " VALIDATION_STRINGENCY=LENIENT HISTOGRAM_FILE=" + props.getProperty(MobileDefinitions.OUTFILE).trim() + "_hist.pdf" +
                " INPUT=" + props.getProperty(MobileDefinitions.INFILE).split(MobileDefinitions.DEFAULT_SEP,0)[0] + " OUTPUT=" + props.getProperty(MobileDefinitions.OUTFILE).trim() + "_insertstats" +
                " STOP_AFTER=50000000";

        execUnixCommand(picardCommand);

        //Values are on line 8
        //[0 = MEDIAN] [4 = MEAN] [17=99percentile]
        BufferedReader br = new BufferedReader(new FileReader(props.getProperty(MobileDefinitions.OUTFILE) + "_insertstats"));
        String line;
        boolean read = false;
        double median;
        int mean = 0;
        double percentile99 = 0;
        int clustermax = 0;
        int sd = 0;

        while ((line = br.readLine()) != null){
            if(read){
                String[] split = line.split("\t", -1);
                median = Double.parseDouble(split[0]);
                mean = (int) Double.parseDouble(split[4]);
                percentile99 = Double.parseDouble(split[17]);
                clustermax = (int) (median + percentile99);
                sd = (int) Double.parseDouble(split[5]);

                break;
            }else if(line.startsWith("MEDIAN_INSERT")){
                read = true;
            }
        }

        br.close();

        if (mean == 0 || clustermax == 0 || sd == 0){
            logger.error("Could not parse the PICARD CollectInsertSizeMetrics successfully");
            System.exit(-1);
        }

        if (props.containsKey(MobileDefinitions.USE_READ_LENGTH) &&
                "true".equalsIgnoreCase(props.getProperty(MobileDefinitions.USE_READ_LENGTH).trim())){
            clustermax = clustermax - Integer.parseInt(props.getProperty(MobileDefinitions.READ_LENGTH).trim());
        }

        props.put(MobileDefinitions.LENGTH_99PROCENT_OF_FRAGMENTS, Integer.toString(clustermax));
        props.put(MobileDefinitions.MEAN_FRAGMENT_LENGTH, Integer.toString(mean));
        props.put(MobileDefinitions.SD_FRAGMENT_LENGTH, Integer.toString(sd));
    }

    private static void extractPotentialMEIReads(final Properties props) throws IOException {
        props.put(MobileDefinitions.INFILE_FROM_POTENTIAL_MEI_FINDER,
                props.getProperty(MobileDefinitions.OUTFILE).trim() + "_potential.bam");
        PotentialMEIReadFinder.runFromProperties(props);
    }

    //do the mobiome mapping here
    private static int runMobiomeMapping(final Properties props) {
        String mobiomeMappingCmd = props.getProperty(MobileDefinitions.MOBIOME_MAPPING_CMD).trim();

        // note that this assumes that upstream task RefAndMEPairFinder outputs "_potential.fq"
        mobiomeMappingCmd = mobiomeMappingCmd.replaceAll("\\(FASTQ\\)", props.getProperty(MobileDefinitions.OUTFILE).trim() + "_potential.fq");

        if (mobiomeMappingCmd.toLowerCase().contains("mosaik")) {
            mobiomeMappingCmd = mobiomeMappingCmd.replaceAll("\\(DAT_FILE\\)", props.getProperty(MobileDefinitions.OUTFILE).trim() + "_potential.dat"); // this line is specific to MOSAIK
        }

        // note that this assumes the aligner used will automatically append ".bam", i.e. it will output "_mappedpotentials.bam"
        mobiomeMappingCmd = mobiomeMappingCmd.replaceAll("\\(OUT_FILE\\)", props.getProperty(MobileDefinitions.OUTFILE).trim() + "_mappedpotentials");

        props.put(MobileDefinitions.INFILE_FROM_MOBIOME_MAPPING,
                props.getProperty(MobileDefinitions.OUTFILE).trim() + "_mappedpotentials.bam");

        return execUnixCommand(mobiomeMappingCmd);
    }

    private static final int CAUGHT_IOEXCEPTION_IN_EXECUTION = -1;
    private static final int PROCESS_INTERRUPTED = -2;
    private static int execUnixCommand(String cmd) {

        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", cmd);
        builder.redirectErrorStream(true);
        try {
            String s;
            Process process = builder.start();
            BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            while ((s = stdout.readLine()) != null) {
                System.out.println(s);
            }
            int exitStatus = process.waitFor();

            System.out.println("Exit value: " + exitStatus);

            return exitStatus;

        } catch (IOException e) {
            logger.error("Error in executing command: " + cmd);
            logger.error(e.getMessage());
            return CAUGHT_IOEXCEPTION_IN_EXECUTION;
        } catch (InterruptedException e) {
            logger.error("Interrupted while executing command: " + cmd);
            logger.error(e.getMessage());
            return PROCESS_INTERRUPTED;
        }
    }

    private static void runMEIPairFinder(final Properties props) throws IOException {
        props.put(MobileDefinitions.ANCHOR_FILE, props.getProperty(MobileDefinitions.OUTFILE).trim() + "_anchors.bam");
        props.put(MobileDefinitions.SPLIT_ANCHOR_FILE, props.getProperty(MobileDefinitions.OUTFILE).trim() + "_splitanchors.bam");
        RefAndMEPairFinder.runFromPropertiesFile(props);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static void printUsage() {
        System.out.println("##########################");
        System.out.println("#MOBSTER                 #");
        System.out.println("##########################");
        System.out.println("Version: " + VERSION);
        System.out.println("Author: Djie Tjwan Thung");
        System.out.println();
        System.out.println("Predict non-reference Mobile Element Insertion (MEI) events using one properties file.");
        System.out.println("None of the arguments below are required, but it is advisable to at least provide a properties file with the IN_FILE, OUT_FILE and SAMPLE_NAME properties set. Otherwise the default file is used.");
        System.out.println("Aside from the arguments below, any property as listed in the default properties file can also be overridden by providing an argument like '--USE_PICARD [value]'.'");
        System.out.println("A new properties file containing all the final properties will also be created with the output prefix.");
        System.out.println("\t-properties [properties]\tPath to the properties file.");
        System.out.println("\t-in [input .bam file].\tThis value will override corresponding value in the properties file. Multiple BAM files may be specified if separated by a comma");
        System.out.println("\t-out [output prefix].\tThis value will override corresponding value in the properties file.");
        System.out.println(("\t-sn [sample name].\tThis value will override corresponding value in the properties file. Multiple sample names may be specified if separated by a comma"));
        System.out.println(("\t-vcf [true / false].\tThis value will override the corresponding value in the properties file. Will change the output of Mobster to VCF instead of the default format."));
        System.out.println(("\t--<PROPERTY> [value].\tThis value will override the corresponding value in the properties file."));
        System.out.println("Default mapping tool: " + SAMDefinitions.MAPPING_TOOL_UNSPECIFIED);
    }

    private static void parseArgs(String[] args, Properties props) {

        try{
            Properties propsFile = new Properties();
            if (args.length > 0 && args[0] != null) {

                //Loop over all properties to find if a properties file has been provided, load it
                //And override all default properties
                for (int i = 0; i < args.length; i = i + 2)
                    if (args[i].equalsIgnoreCase("-properties")) {
                        propertiesFile = args[i + 1];
                        propsFile.load(new FileInputStream(propertiesFile));
                        props.putAll(propsFile);
                    }

                //Again loop over all arguments and again override any properties in props when they are provided on the command line
                for (int i = 0; i < args.length; i = i + 2) {
                    String argument = args[i];
                    String argUpperCase = argument.replace("--", "").toUpperCase();
                    String value = args[i+1];

                    if (argument.equalsIgnoreCase("-in")){
                        inFile = value;
                        props.put(MobileDefinitions.INFILE, inFile);
                    } else if (argument.equalsIgnoreCase("-out")){
                        outFile = value;
                        props.put(MobileDefinitions.OUTFILE, outFile);
                    } else if (argument.equalsIgnoreCase("-sn")){
                        sampleName = value;
                        props.put(MobileDefinitions.SAMPLE_NAME, sampleName);
                    }
                    else if (argument.equalsIgnoreCase("-vcf")){
                        vcfOut = Boolean.parseBoolean(value);
                        props.put(MobileDefinitions.VCF, value);
                    }
                    //When a property present in the default properties is provided, it is overridden.
                    else if(argument.startsWith("--")
                            && props.containsKey(argUpperCase)){
                        props.put(argUpperCase, value);
                    } else if(!argument.equals("-properties")){
                        System.out.println(argument);

                        logger.error("Invalid arguments. Please try again.");
                        printUsage();
                        System.exit(-1);
                    }
                }

                //Check whether there is a correct number of samples
                if (props.getProperty(MobileDefinitions.SAMPLE_NAME).split(MobileDefinitions.DEFAULT_SEP, 0).length !=
                        props.getProperty(MobileDefinitions.INFILE).split(MobileDefinitions.DEFAULT_SEP, 0).length){
                    logger.error("Number of supplied samples does not equal the number of supplied bams. Exiting");
                    System.exit(1);
                } else if (props.getProperty(MobileDefinitions.SAMPLE_NAME).split(MobileDefinitions.DEFAULT_SEP, 0).length > 1){
                    logger.info("Detected multiple samples. Multiple sample calling will be turned ON even if this property was set to false in the properties file");
                    props.put(MobileDefinitions.MULTIPLE_SAMPLE_CALLING, "true");
                }

                //Write the current properties to a file
                String output = props.getProperty(MobileDefinitions.OUTFILE) + "_Mobster.properties";
                props.store(new FileOutputStream(output), "");

            } else {
                logger.error("No arguments provided. Please try again.");
                printUsage();
                System.exit(-1);
            }
        } catch(ArrayIndexOutOfBoundsException e){
            logger.error("Invalid argument value provided. Please try again. Make sure each argument is followed by a value.");
            printUsage();
            System.exit(-1);
        } catch (FileNotFoundException e) {
            logger.error(e.getMessage());
            System.exit(-1);
        } catch (IOException e) {
            logger.error(e.getMessage());
            System.exit(-1);
        }
    }

}