package com.castsoftware.dmt.extractor.mavenbuild;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.castsoftware.dmt.engine.extraction.AbstractBlankInitialRootExtractor;
import com.castsoftware.util.FileHelper;
import com.castsoftware.util.logger.Logging;
import com.castsoftware.util.logger.exceptions.LogException;

/**
 * The {@link MavenBuildExtractor} class is the class responsible for extracting sources from file system. <BR>
 */
public class MavenBuildExtractor extends AbstractBlankInitialRootExtractor
{

	/**
	 * Folder extractor constructor
	 */
    public MavenBuildExtractor()
    {
        // NOP
    }

    @Override
    public void connect(String url, String user, String pswd, IConfiguration configuration) throws LogException
    {
        File rootFile = new File(url);
        if (!rootFile.isAbsolute())
            throw Logging.error("cast.dmt.extractor.mavenbuild.extractionURLNotAbsoluteFailure", "FILE", rootFile);
        if (!rootFile.exists())
            throw Logging.error("cast.dmt.extractor.mavenbuild.extractionURLNotFoundFailure", "FILE", rootFile);
    }

    @Override
    public void disconnect(IConfiguration configuration) throws LogException
    {
        // Nothing
    	return;
    }

    @Override
    public void getChildren(Map<String, Iterable<BrowseEntry>> pathsChildren, IConfiguration configuration) throws LogException
    {
    	// Not supported - action is hidden
    	return;
    }

    @Override
    public void extract(List<? extends ISourceRoot> initialRoots, IRootFactory rootFactory, IConfiguration configuration)
        throws LogException
    {
        ISourceRoot root = initialRoots.get(0);

        Map<String, File> jarFiles = new HashMap<String, File>();
    	Map<String, File> darFiles = new HashMap<String, File>();
    	Map<String, File> earFiles = new HashMap<String, File>();
    	
        File globalRootFile = new File(configuration.getURL());

        // 1. identify the list of files to extract and the mode
    	for (File f : globalRootFile.listFiles())
    	{
    		String filename = f.getName().toLowerCase();
    		if (filename.endsWith(".jar"))
    		{
    			int pos = 0;
    			if (filename.contains("-sources.jar"))
    				pos = filename.length() - "-sources.jar".length();
    			else
    				pos = filename.length() - ".jar".length();
    			
    			if (pos > 0)
    				jarFiles.put(filename.substring(0, pos), f);
    		}
    		if (filename.endsWith(".dar"))
    		{
    			int pos = filename.lastIndexOf("dar-");
    			int posExtension = filename.lastIndexOf(".dar");
    			String darname = filename.substring(0, pos);
    			if (pos > 0)
    				darFiles.put(darname + filename.substring(pos + 3, posExtension), f);
    		}
    		if (filename.endsWith(".ear"))
    		{
    			earFiles.put(filename.substring(0, filename.length() - 4), f);
    		}
    	}
    	
    	// 2. extract the files in the temp folder
    	for (Map.Entry<String, File> entry : darFiles.entrySet()) {
    	    String key = entry.getKey();
    	    File value = entry.getValue();
    		// 2.1 extract the dar
    	    extractDarFiles(key, value, root.getContentDirectoryFile());
    	    
    		// 2.2 extract the corresponding jar
    		if (jarFiles.containsKey(key))
    		{
    			// extract
    			Logging.info("cast.dmt.extractor.mavenbuild.jarMatchingDar", "JAR", key);
    			extractJarFile(key, jarFiles.get(key), root.getContentDirectoryFile());
    			// remove
    			jarFiles.remove(key);
    		}
    	}
    	
    	// 2. extract the files in the temp folder
    	for (Map.Entry<String, File> entry : jarFiles.entrySet()) {
    	    String key = entry.getKey();
    	    File value = entry.getValue();
			Logging.info("cast.dmt.extractor.mavenbuild.jarWithoutDar", "JAR", key);
			extractJarFile(key, jarFiles.get(key), root.getContentDirectoryFile());
    	}
    }

    private static void extractWarFiles(String key, File warfile, File destinationFolder)
    {
		Logging.info("cast.dmt.extractor.mavenbuild.extractingWarFile", "WAR", key);
		String pomFilePath = null;
		List<String> jarFiles = new ArrayList<String>();

        try
        {
            String destinationName = FileHelper.getPortablePath(destinationFolder.getCanonicalPath());
            destinationName += "/" + key.substring(0, key.lastIndexOf("-"));
            pomFilePath = destinationName + "/pom.xml";
            destinationName +=  "/src/main/webapp/";
            byte[] buf = new byte[1024];
            ZipInputStream zipinputstream = null;
            ZipEntry zipentry;
            zipinputstream = new ZipInputStream(new FileInputStream(warfile.getCanonicalPath()));
 
            zipentry = zipinputstream.getNextEntry();
            while (zipentry != null) 
            { 
                //for each entry to be extracted
                String entryName = zipentry.getName();
                
                //System.out.println("entryname "+entryName);
                if (entryName.endsWith("/"))
                {
                    File newFile = new File(destinationName + entryName);
                	newFile.mkdirs();
                	zipentry = zipinputstream.getNextEntry();
                	continue;
                }
                if (entryName.endsWith(".jar"))
                	jarFiles.add(entryName);
                 
                int n;
                FileOutputStream fileoutputstream;
                if (entryName.endsWith("pom.xml"))
	                fileoutputstream = new FileOutputStream(pomFilePath);             
                else
	                fileoutputstream = new FileOutputStream(destinationName + entryName);             
	 
                while ((n = zipinputstream.read(buf, 0, 1024)) > -1)
                    fileoutputstream.write(buf, 0, n);
 
                fileoutputstream.close(); 
                zipinputstream.closeEntry();
                zipentry = zipinputstream.getNextEntry();
            }
 
            zipinputstream.close();
            
            File pomFile = new File(pomFilePath);
            if (pomFile.exists())
            	transformPom(pomFilePath, jarFiles);
        }
        catch (Exception e)
        {
            //e.printStackTrace();
        }
    }

    private static void transformPom(String pomFilePath, List<String> jarFiles)
    {
    	//String pomContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    	BufferedReader reader = null;
    	StringBuffer sbf = new StringBuffer();
        BufferedWriter bwr = null;
        Boolean isWarPackaging = false;
        try
        {
        	String pomContent = readFile(pomFilePath);
    		sbf.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    		sbf.append("\r\n");
    		sbf.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">");
        	sbf.append("\r\n");
        	sbf.append("<modelVersion>4.0.0</modelVersion>");
        	sbf.append("\r\n");
        	if (pomContent != null)
        	{
            	String groupId = null;
            	Boolean isInGroupId = false;
            	String artifactId = null;
            	Boolean isInArtifactId = false;
            	String version = null;
            	Boolean isInVersion = false;
            	String packaging = null;
            	Boolean isInPackaging = false;
            	String name = null;
            	Boolean isInName = false;
            	
                Boolean isInParent = false;
            	StringBuffer parent = new StringBuffer();
                Boolean isInProperties = false;
            	StringBuffer properties = new StringBuffer();
                Boolean isInBuild = false;
                Boolean isInRepositories = false;
                Boolean isInPluginRepositories = false;
                Boolean isInReporting = false;
                Boolean isInProfiles = false;
                Boolean isInDependencies = false;
                Boolean isInDependencyManagement = false;
                Boolean isInScm= false;

            	reader = new BufferedReader(new StringReader(pomContent), pomContent.length());

            	int numline = 0;
	            for (String readline = reader.readLine(); readline != null; readline = reader.readLine())
	            {
	            	numline++;
	            	String line = readline.trim();
	            	if (line.isEmpty())
	            		continue;
 		        	if (line.contains("<parent>"))
		        		isInParent = true;
		        	else if (line.contains("</parent>"))
		        	{
		        		isInParent = false;
		        		parent.append(readline);
		        		parent.append("\r\n");
		        		continue;
		        	}
		        	else if (line.contains("<properties>"))
		        		isInProperties = true;
		        	else if (line.contains("</properties>"))
		        	{
		        		isInProperties = false;
		        		properties.append(readline);
		        		properties.append("\r\n");
		        		continue;
		        	}
		        	else if (line.contains("<build>"))
		        		isInBuild = true;
		        	else if (line.contains("</build>"))
		        	{
		        		isInBuild = false;
		        		continue;
		        	}
		        	else if (line.contains("<repositories>"))
		        		isInRepositories = true;
		        	else if (line.contains("</repositories>"))
		        	{
		        		isInRepositories = false;
		        		continue;
		        	}
		        	else if (line.contains("<pluginRepositories>"))
		        		isInPluginRepositories = true;
		        	else if (line.contains("</pluginRepositories>"))
		        	{
		        		isInPluginRepositories = false;
		        		continue;
		        	}
		        	else if (line.contains("<reporting>"))
		        		isInReporting = true;
		        	else if (line.contains("</reporting>"))
		        	{
		        		isInReporting = false;
		        		continue;
		        	}
		        	else if (line.contains("<profiles>"))
		        		isInProfiles = true;
		        	else if (line.contains("</profiles>"))
		        	{
		        		isInProfiles = false;
		        		continue;
		        	}
		        	else if (line.contains("<dependencies>"))
		        		isInDependencies = true;
		        	else if (line.contains("</dependencies>"))
		        	{
		        		isInDependencies = false;
		        		continue;
		        	}
		        	else if (line.contains("<dependencyManagement>"))
		        		isInDependencyManagement = true;
		        	else if (line.contains("</dependencyManagement>"))
		        	{
		        		isInDependencyManagement = false;
		        		continue;
		        	}
		        	else if (line.contains("<scm>"))
		        		isInScm = true;
		        	else if (line.contains("</scm>"))
		        	{
		        		isInScm = false;
		        		continue;
		        	}
		        	
		        	if (isInParent)
		        	{
		        		parent.append(readline);
		        		parent.append("\r\n");
		        	}
		        	else if (isInProperties)
		        	{
		        		properties.append(readline);
		        		properties.append("\r\n");
		        	}
		        	else if (isInBuild || isInRepositories || isInPluginRepositories || isInReporting || isInProfiles || isInDependencies || isInDependencyManagement || isInScm)
		        		continue;
		        	
		        	if (line.startsWith("<groupId>"))
		        	{
	        			groupId = line;
		        		if (!line.endsWith("</groupId>"))
		        			isInGroupId = true;
		        	}
		        	else if (line.startsWith("<artifactId>"))
		        	{
		        		artifactId = line;
		        		if (!line.endsWith("</artifactId>"))
		        			isInArtifactId = true;
		        	}
		        	else if (line.startsWith("<version>"))
		        	{
		        		version = line;
		        		if (!line.endsWith("</version>"))
		        			isInVersion = true;
		        	}
		        	else if (line.startsWith("<packaging>"))
		        	{
		        		packaging = line;
		        		if (!line.endsWith("</packaging>"))
		        			isInPackaging = true;
		        		else
			        		if (packaging.toLowerCase().contains("war"))
			        			isWarPackaging = true;

		        	}
		        	else if (line.startsWith("<name>"))
		        	{
		        		name = line;
		        		if (!line.endsWith("</name>"))
		        			isInName = true;
		        	}
		        	else
		        	{
		        		if (isInGroupId)
		        		{
		        			groupId += line;
		        			if (line.endsWith("</groupId>"))
		        				isInGroupId = false;
		        		}
		        		else if (isInArtifactId)
		        		{
		        			artifactId += line;
		        			if (line.endsWith("</artifactId>"))
		        				isInArtifactId = false;
		        		}
		        		else if (isInVersion)
		        		{
		        			version += line;
		        			if (line.endsWith("</version>"))
		        				isInVersion = false;
		        		}
		        		else if (isInPackaging)
		        		{
		        			packaging += line;
		        			if (line.endsWith("</packaging>"))
		        			{
		        				isInPackaging = false;
				        		if (packaging.toLowerCase().contains("war"))
				        			isWarPackaging = true;
		        			}
		        		}
		        		else if (isInName)
		        		{
		        			name += line;
		        			if (line.endsWith("</name>"))
		        				isInName = false;
		        		}
		        	}
	            }
				reader.close();
				reader = null;
				
            	String groupIdParent = "groupId";
            	String artifactIdParent = "artifactId";
            	String versionParent = "version";

            	if (groupId.length() > 0)
            		sbf.append(groupId);
            	else
            	{
            		sbf.append("<groupId>");
            		sbf.append(groupIdParent);
    	        	sbf.append("</groupId>");
            	}
	        	sbf.append("\r\n");
	        	if (artifactId.length() > 0)
		        	sbf.append(artifactId);
	        	else
	        	{
		        	sbf.append("<artifactId>");
            		sbf.append(artifactIdParent);
		        	sbf.append("</artifactId>");
	        	}
	        	sbf.append("\r\n");
	        	if (packaging != null && packaging.length() > 0)
		        	sbf.append(packaging);
	        	else
	        	{
	        		sbf.append("<packaging>jar</packaging>");
	        	}
	        	sbf.append("\r\n");
	        	if (version.length() > 0)
		        	sbf.append(version);
	        	else
	        	{
	        		sbf.append("<version>");
	        		sbf.append(versionParent);
	        		sbf.append("</version>");
	        	}
	        	sbf.append("\r\n");
	        	if (name != null && name.length() > 0)
		        	sbf.append(name);
	        	else
	        		sbf.append("<name>${artifactId}</name>");
	        	sbf.append("\r\n");
	        	
	        	sbf.append(properties);
        	}
        	
        	sbf.append("<build>");
        	sbf.append("\r\n");
        	sbf.append("<sourceDirectory>src/main/java</sourceDirectory>");
        	sbf.append("\r\n");
        	if (isWarPackaging)
        	{
            	sbf.append("<plugins>");
            	sbf.append("\r\n");
            	sbf.append("<plugin>");
            	sbf.append("\r\n");
            	sbf.append("<artifactId>maven-war-plugin</artifactId>");
            	sbf.append("\r\n");
            	sbf.append("<configuration>");
            	sbf.append("\r\n");
            	sbf.append("<warSourceDirectory>src/main/webapp</warSourceDirectory>");
            	sbf.append("\r\n");
            	sbf.append("</configuration>");
            	sbf.append("\r\n");
            	sbf.append("</plugin>");
            	sbf.append("\r\n");
            	sbf.append("</plugins>");
            	sbf.append("\r\n");        		
        	}
        	sbf.append("</build>");
        	sbf.append("\r\n");
        	//TODO: add the list of jar files as dependencies
        	sbf.append("</project>");

        	File pomFile = new File(pomFilePath);
        	pomFile.delete();

    		//bwr = new BufferedWriter(new FileWriter(new File(pomFilePath)));
    		bwr = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(pomFilePath), Charset.forName("UTF-8")));
    		bwr.write(sbf.toString());
			bwr.flush();
    		bwr.close();
    		bwr = null;
        }
        catch (IllegalArgumentException e)
        {
            Logging.managedError(e, "cast.dmt.extractor.mavenbuild.ioExceptionInPomParsing", "PATH",
            		pomFilePath);
        }
        catch (IOException e)
        {
            Logging.managedError(e, "cast.dmt.extractor.mavenbuild.ioExceptionInPomParsing", "PATH",
            		pomFilePath);
        }
        finally
        {
        	try
        	{
        		if (reader != null)
        			reader.close();
	        	if (bwr != null)
	        	{
					bwr.flush();
		        	bwr.close();
	        	}
        	}
        	catch (IOException e)
        	{
                Logging.managedError(e, "cast.dmt.extractor.mavenbuild.ioExceptionInPomParsing", "PATH",
                		pomFilePath);
			}
        }
    }
    
    private static void extractEarFiles(String key, File earfile, File destinationFolder)
    {
		Logging.info("cast.dmt.extractor.mavenbuild.extractingEarFile", "EAR", key);

        try
        {
            String destinationName = FileHelper.getPortablePath(destinationFolder.getCanonicalPath());
            destinationName += "/" + key.substring(0, key.lastIndexOf("-")) + "/";
            byte[] buf = new byte[1024];
            ZipInputStream zipinputstream = null;
            ZipEntry zipentry;
            zipinputstream = new ZipInputStream(new FileInputStream(earfile.getCanonicalPath()));
 
            zipentry = zipinputstream.getNextEntry();
            while (zipentry != null) 
            { 
                //for each entry to be extracted
                String entryName = zipentry.getName();
                //System.out.println("entryname "+entryName);
                if (!entryName.endsWith(".war"))
                {
                	zipentry = zipinputstream.getNextEntry();
                	continue;
                }
                 
                int n;
                FileOutputStream fileoutputstream;
                fileoutputstream = new FileOutputStream(destinationName + entryName);             
 
                while ((n = zipinputstream.read(buf, 0, 1024)) > -1)
                    fileoutputstream.write(buf, 0, n);
 
                fileoutputstream.close(); 
                zipinputstream.closeEntry();
                
                File warFile = new File(destinationName + entryName);
                extractWarFiles(key, warFile, destinationFolder);
                warFile.delete();
                
                zipentry = zipinputstream.getNextEntry();
 
            }//while
 
            zipinputstream.close();
        }
        catch (Exception e)
        {
            //e.printStackTrace();
        }
    }
    
    private static void extractDarFiles(String key, File darfile, File destinationFolder)
    {
		Logging.info("cast.dmt.extractor.mavenbuild.extractingDarFile", "DAR", key);

        try
        {
            String destinationName = FileHelper.getPortablePath(destinationFolder.getCanonicalPath());
            destinationName += "/" + key.substring(0, key.lastIndexOf("-")) + "/";
            File fld = new File(destinationName);
            fld.mkdir();
            byte[] buf = new byte[1024];
            ZipInputStream zipinputstream = null;
            ZipEntry zipentry;
            zipinputstream = new ZipInputStream(new FileInputStream(darfile.getCanonicalPath()));
 
            zipentry = zipinputstream.getNextEntry();
            while (zipentry != null) 
            { 
                //for each entry to be extracted
                String entryName = zipentry.getName();
                //System.out.println("entryname "+entryName);
                if (!entryName.endsWith(".ear"))
                {
                	zipentry = zipinputstream.getNextEntry();
                	continue;
                }
                 
                int n;
                FileOutputStream fileoutputstream;
                String fileName = entryName.substring(entryName.lastIndexOf("/") + 1);
                fileoutputstream = new FileOutputStream(destinationName + fileName);             
 
                while ((n = zipinputstream.read(buf, 0, 1024)) > -1)
                    fileoutputstream.write(buf, 0, n);
 
                fileoutputstream.close(); 
                zipinputstream.closeEntry();
                
                File earFile = new File(destinationName + fileName);
                extractEarFiles(key, earFile, destinationFolder);
                earFile.delete();
                
                zipentry = zipinputstream.getNextEntry();
 
            }
 
            zipinputstream.close();
        }
        catch (Exception e)
        {
            //e.printStackTrace();
        }
    }
    
    private static String readFile(String file) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader (file));
        String         line = null;
        StringBuilder  stringBuilder = new StringBuilder();
        String         ls = System.getProperty("line.separator");

        try {
            while((line = reader.readLine()) != null) {
                stringBuilder.append(line);
                stringBuilder.append(ls);
            }

            return stringBuilder.toString();
        } finally {
            reader.close();
        }
    }    
    
    private static void extractJarFile(String key, File jarfile, File destinationFolder)
    {
        try
        {
            String destinationName = FileHelper.getPortablePath(destinationFolder.getCanonicalPath());
            destinationName += "/" + key.substring(0, key.lastIndexOf("-"));
            String pomFilePath = destinationName + "/pom.xml";
            destinationName +=  "/src/main/java/";
            // remove the version
            //destinationName += "/" + key.substring(0, key.lastIndexOf("-")) + "/src/main/java/";
            byte[] buf = new byte[1024];
            ZipInputStream zipinputstream = null;
            ZipEntry zipentry;
            zipinputstream = new ZipInputStream(new FileInputStream(jarfile.getCanonicalPath()));
 
            zipentry = zipinputstream.getNextEntry();
            while (zipentry != null) 
            { 
                //for each entry to be extracted
                String entryName = zipentry.getName();
                //System.out.println("entryname "+entryName);

                if (entryName.endsWith("/"))
                {
                    File newFile = new File(destinationName + entryName);
                	newFile.mkdirs();
                	zipentry = zipinputstream.getNextEntry();
                	continue;
                }
                int n;
                FileOutputStream fileoutputstream;
                if (entryName.endsWith("pom.xml"))
	                fileoutputstream = new FileOutputStream(pomFilePath);             
                else
	                fileoutputstream = new FileOutputStream(destinationName + entryName);             
 
                while ((n = zipinputstream.read(buf, 0, 1024)) > -1)
                    fileoutputstream.write(buf, 0, n);
 
                fileoutputstream.close(); 
                zipinputstream.closeEntry();
                zipentry = zipinputstream.getNextEntry();
 
            }//while
 
            zipinputstream.close();

            File pomFile = new File(pomFilePath);
            if (pomFile.exists())
            	transformPom(pomFilePath, null);
            else
            	Logging.warn("cast.dmt.extractor.mavenbuild.noPomInJar", "JAR", key);
        }
        catch (Exception e)
        {
            //e.printStackTrace();
        }
    }
}
