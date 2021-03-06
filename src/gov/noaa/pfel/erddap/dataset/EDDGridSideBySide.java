/* 
 * EDDGridSideBySide Copyright 2008, NOAA.
 * See the LICENSE.txt file in this file's directory.
 */
package gov.noaa.pfel.erddap.dataset;

import com.cohort.array.Attributes;
import com.cohort.array.ByteArray;
import com.cohort.array.IntArray;
import com.cohort.array.PrimitiveArray;
import com.cohort.array.StringArray;
import com.cohort.util.Calendar2;
import com.cohort.util.File2;
import com.cohort.util.MustBe;
import com.cohort.util.SimpleException;
import com.cohort.util.String2;
import com.cohort.util.Test;

import gov.noaa.pfel.coastwatch.griddata.NcHelper;
import gov.noaa.pfel.coastwatch.pointdata.Table;
import gov.noaa.pfel.coastwatch.sgt.SgtGraph;
import gov.noaa.pfel.coastwatch.sgt.SgtMap;
import gov.noaa.pfel.coastwatch.util.SimpleXMLReader;
import gov.noaa.pfel.coastwatch.util.SSR;
import gov.noaa.pfel.erddap.util.EDStatic;
import gov.noaa.pfel.erddap.variable.*;

import java.util.ArrayList;
import java.util.Arrays;

/** 
 * This class represents a grid dataset created by aggregating 
 *   two or more datasets side by side.
 * So the resulting dataset has all the variables of the
 *   child datasets.
 * This class makes a new axis[0] with the union of all axis[0] values
 *   from all children.
 * All children must have the same values for axis[1+]. 
 * <p>If there is an exception while creating the first child, this throws the exception.
 * If there is an exception while creating other children, this emails
 *    EDStatic.emailEverythingToCsv and continues.
 * <p>Children created by this method are held privately.
 *   They are not separately accessible datasets (e.g., by queries or by flag files).
 * 
 * @author Bob Simons (bob.simons@noaa.gov) 2008-02-04
 */
public class EDDGridSideBySide extends EDDGrid { 

    protected EDDGrid childDatasets[];
    protected int childStopsAt[]; //the last valid dataVariables index for each childDataset
    protected IntArray indexOfAxis0Value[]; //an IntArray for each child; a row for each axis0 value


    /**
     * This constructs an EDDGridSideBySide based on the information in an .xml file.
     * Only the global attributes from the first dataset are used for the composite
     * dataset.
     * 
     * @param xmlReader with the &lt;erddapDatasets&gt;&lt;dataset type="EDDGridSideBySide"&gt; 
     *    having just been read.  
     * @return an EDDGridSideBySide.
     *    When this returns, xmlReader will have just read &lt;erddapDatasets&gt;&lt;/dataset&gt; .
     * @throws Throwable if trouble
     */
    public static EDDGridSideBySide fromXml(SimpleXMLReader xmlReader) throws Throwable {

        if (verbose) String2.log("\n*** constructing EDDGridSideBySide(xmlReader)...");
        String tDatasetID = xmlReader.attributeValue("datasetID"); 

        //data to be obtained while reading xml
        ArrayList tChildDatasets = new ArrayList();
        StringBuilder messages = new StringBuilder();
        String tAccessibleTo = null;
        StringArray tOnChange = new StringArray();
        String tFgdcFile = null;
        String tIso19115File = null;
        String tDefaultDataQuery = null;
        String tDefaultGraphQuery = null;

        //process the tags
        String startOfTags = xmlReader.allTags();
        int startOfTagsN = xmlReader.stackSize();
        int startOfTagsLength = startOfTags.length();

        while (true) {
            xmlReader.nextTag();
            String tags = xmlReader.allTags();
            String content = xmlReader.content();
            if (reallyVerbose) String2.log("  tags=" + tags + content);
            if (xmlReader.stackSize() == startOfTagsN) 
                break; //the </dataset> tag
            String localTags = tags.substring(startOfTagsLength);

            //try to make the tag names as consistent, descriptive and readable as possible
            if (localTags.equals("<dataset>")) {
                try {
                    EDD edd = EDD.fromXml(xmlReader.attributeValue("type"), xmlReader);
                    if (edd instanceof EDDGrid) {
                        tChildDatasets.add(edd);
                    } else {
                        throw new RuntimeException("The datasets defined in an " +
                            "EDDGridSideBySide must be a subclass of EDDGrid.");
                    }
                } catch (Throwable t) {
                    //exceptions for first child are serious (it has parent's metadata)
                    if (tChildDatasets.size() == 0) 
                        throw t;

                    //exceptions for others are noted, but construction continues
                    String2.log(MustBe.throwableToString(t));
                    messages.append(MustBe.throwableToString(t) + "\n");

                    //read to </dataset>
                    while (xmlReader.stackSize() != startOfTagsN + 1 ||
                           !xmlReader.allTags().substring(startOfTagsLength).equals("</dataset>")) {
                        xmlReader.nextTag();
                        //String2.log("  skippping tags: " + xmlReader.allTags());
                    }
                }

            } else if (localTags.equals( "<onChange>")) {}
            else if (localTags.equals("</onChange>")) tOnChange.add(content); 
            else if (localTags.equals( "<accessibleTo>")) {}
            else if (localTags.equals("</accessibleTo>")) tAccessibleTo = content;
            else if (localTags.equals( "<fgdcFile>")) {}
            else if (localTags.equals("</fgdcFile>"))     tFgdcFile = content; 
            else if (localTags.equals( "<iso19115File>")) {}
            else if (localTags.equals("</iso19115File>")) tIso19115File = content; 
            else if (localTags.equals( "<defaultDataQuery>")) {}
            else if (localTags.equals("</defaultDataQuery>")) tDefaultDataQuery = content; 
            else if (localTags.equals( "<defaultGraphQuery>")) {}
            else if (localTags.equals("</defaultGraphQuery>")) tDefaultGraphQuery = content; 

            else xmlReader.unexpectedTagException();
        }
        if (messages.length() > 0) {
            EDStatic.email(EDStatic.emailEverythingToCsv, 
                "Error in ERDDAP EDDGridSideBySide constructor for " + tDatasetID, 
                messages.toString());
        }

        EDDGrid tcds[] = new EDDGrid[tChildDatasets.size()];
        for (int c = 0; c < tChildDatasets.size(); c++)
            tcds[c] = (EDDGrid)tChildDatasets.get(c);

        //make the main dataset based on the information gathered
        return new EDDGridSideBySide(tDatasetID, tAccessibleTo, 
            tOnChange, tFgdcFile, tIso19115File,
            tDefaultDataQuery, tDefaultGraphQuery, tcds);

    }

    /**
     * The constructor.
     * The axisVariables must be the same and in the same
     * order for each dataVariable.
     *
     * @param tDatasetID
     * @param tAccessibleTo is a comma separated list of 0 or more
     *    roles which will have access to this dataset.
     *    <br>If null, everyone will have access to this dataset (even if not logged in).
     *    <br>If "", no one will have access to this dataset.
     * @param tOnChange 0 or more actions (starting with "http://" or "mailto:")
     *    to be done whenever the dataset changes significantly
     * @param tFgdcFile This should be the fullname of a file with the FGDC
     *    that should be used for this dataset, or "" (to cause ERDDAP not
     *    to try to generate FGDC metadata for this dataset), or null (to allow
     *    ERDDAP to try to generate FGDC metadata for this dataset).
     * @param tIso19115 This is like tFgdcFile, but for the ISO 19119-2/19139 metadata.
     * @param tChildDatasets
     * @throws Throwable if trouble
     */
    public EDDGridSideBySide(String tDatasetID, String tAccessibleTo,
        StringArray tOnChange, String tFgdcFile, String tIso19115File, 
        String tDefaultDataQuery, String tDefaultGraphQuery, 
        EDDGrid tChildDatasets[]) throws Throwable {

        if (verbose) String2.log("\n*** constructing EDDGridSideBySide " + tDatasetID); 
        long constructionStartMillis = System.currentTimeMillis();
        String errorInMethod = "Error in EDDGridGridSideBySide(" + 
            tDatasetID + ") constructor:\n";
            
        //save some of the parameters
        className = "EDDGridSideBySide"; 
        datasetID = tDatasetID;
        setAccessibleTo(tAccessibleTo);
        onChange = tOnChange;
        fgdcFile = tFgdcFile;
        iso19115File = tIso19115File;
        defaultDataQuery = tDefaultDataQuery;
        defaultGraphQuery = tDefaultGraphQuery;
        childDatasets = tChildDatasets;
        int nChildren = tChildDatasets.length;
        childStopsAt = new int[nChildren];

        //check the siblings and create childStopsAt
        EDDGrid firstChild = childDatasets[0];
        int nAV = firstChild.axisVariables.length;
        for (int c = 0; c < nChildren; c++) {
            if (c > 0) {
                String similar = firstChild.similarAxisVariables(childDatasets[c], 1, false); //test axes 1+
                if (similar.length() > 0)
                    throw new RuntimeException("Error: Datasets #0 and #" + c + " are not similar: " + similar);

                //since they are similar, save memory by pointing to same datastructures
                //but just copy axisVariable 1+   (because axisVariables[0].sourceValues may be different)
                System.arraycopy(firstChild.axisVariables, 1, childDatasets[c].axisVariables, 1, nAV - 1);
                childDatasets[c].axisVariableSourceNames      = firstChild.axisVariableSourceNames(); //() makes the array
                childDatasets[c].axisVariableDestinationNames = firstChild.axisVariableDestinationNames();
                //not id
                childDatasets[c].title                        = firstChild.title();
                childDatasets[c].summary                      = firstChild.summary();
                childDatasets[c].institution                  = firstChild.institution();
                childDatasets[c].infoUrl                      = firstChild.infoUrl();
                childDatasets[c].cdmDataType                  = firstChild.cdmDataType();
                childDatasets[c].searchBytes                  = firstChild.searchBytes();
                //not sourceUrl, which will be different
                childDatasets[c].sourceGlobalAttributes       = firstChild.sourceGlobalAttributes();
                childDatasets[c].addGlobalAttributes          = firstChild.addGlobalAttributes();
                childDatasets[c].combinedGlobalAttributes     = firstChild.combinedGlobalAttributes();

            }
            childStopsAt[c] = (c == 0? -1 : childStopsAt[c - 1]) + childDatasets[c].dataVariables().length;
        }

        //create indexOfAxis0Value: an IntArray for each child; a row for each axis0 value
        int rowPo[] = new int[nChildren]; //all initially 0
        indexOfAxis0Value = new IntArray[nChildren];
        //gather all axis0SourceValues
        PrimitiveArray axis0SourceValues[] = new PrimitiveArray[nChildren];
        for (int c = 0; c < nChildren; c++) {
            indexOfAxis0Value[c] = new IntArray();
            axis0SourceValues[c] = childDatasets[c].axisVariables[0].sourceValues();
            if (verbose) String2.log("child[" + c + "].axisVariables[0].size()=" + axis0SourceValues[c].size());
        }
        PrimitiveArray newAxis0Values = PrimitiveArray.factory(
            axis0SourceValues[0].elementClass(), axis0SourceValues[0].size() + 100, false);
        while (true) {
            //repeatedly: 
            //find children which share the lowest axis0 value  (all axis values are numeric and not-NaN)
            double lowestValue = Double.MAX_VALUE;
            int firstLowestC = -1;
            boolean hasLowest[] = new boolean[nChildren]; //initially all false
            for (int c = 0; c < nChildren; c++) {
                if (rowPo[c] < axis0SourceValues[c].size()) {
                    double td = axis0SourceValues[c].getDouble(rowPo[c]);
                    if (td < lowestValue) {
                        lowestValue = td;
                        if (firstLowestC >= 0)
                            Arrays.fill(hasLowest, firstLowestC, c, false);
                        firstLowestC = c;
                        hasLowest[c] = true;                        
                    } else if (td == lowestValue) {
                        hasLowest[c] = true;
                    }
                }
            }
            if (firstLowestC == -1) //all children are done
                break;

            //note which row lowest is on 
            newAxis0Values.addDouble(lowestValue);
            //StringBuilder tsb = new StringBuilder("lowestValue=" + lowestValue);
            for (int c = 0; c < nChildren; c++) {
                if (hasLowest[c]) {
                    //tsb.append(" " + rowPo[c]);
                    indexOfAxis0Value[c].add(rowPo[c]++); //and increment those rowPo's
                } else {
                    //tsb.append(" NaN");
                    indexOfAxis0Value[c].add(Integer.MAX_VALUE);
                }
            }
            //String2.log(tsb.toString());
        }

        //create the aggregate dataset
        setReloadEveryNMinutes(firstChild.getReloadEveryNMinutes());
      
        localSourceUrl = firstChild.localSourceUrl();
        addGlobalAttributes = firstChild.addGlobalAttributes();
        sourceGlobalAttributes = firstChild.sourceGlobalAttributes();
        combinedGlobalAttributes = firstChild.combinedGlobalAttributes();
        //and clear searchString of children?

        //duplicate firstChild.axisVariables
        lonIndex   = firstChild.lonIndex;
        latIndex   = firstChild.latIndex;
        altIndex   = firstChild.altIndex;
        depthIndex = firstChild.depthIndex;
        timeIndex  = firstChild.timeIndex;
        axisVariables = new EDVGridAxis[nAV];
        System.arraycopy(firstChild.axisVariables, 1, axisVariables, 1, nAV - 1);
        //but make new axisVariables[0] with newAxis0Values
        EDVGridAxis fav = firstChild.axisVariables[0];
        if (lonIndex == 0)
            axisVariables[0] = new EDVLonGridAxis(fav.sourceName(), 
                fav.sourceAttributes(), fav.addAttributes(), newAxis0Values); 
        else if (latIndex == 0)
            axisVariables[0] = new EDVLatGridAxis(fav.sourceName(), 
                fav.sourceAttributes(), fav.addAttributes(), newAxis0Values); 
        else if (altIndex == 0)
            axisVariables[0] = new EDVAltGridAxis(fav.sourceName(), 
                fav.sourceAttributes(), fav.addAttributes(), newAxis0Values); 
        else if (depthIndex == 0)
            axisVariables[0] = new EDVDepthGridAxis(fav.sourceName(), 
                fav.sourceAttributes(), fav.addAttributes(), newAxis0Values); 
        else if (timeIndex == 0)
            axisVariables[0] = new EDVTimeGridAxis(fav.sourceName(), 
                fav.sourceAttributes(), fav.addAttributes(), newAxis0Values); 
        else if (fav instanceof EDVTimeStampGridAxis) 
            axisVariables[0] = new EDVTimeStampGridAxis(
                fav.sourceName(), fav.destinationName(),
                fav.sourceAttributes(), fav.addAttributes(), newAxis0Values); 
        else {axisVariables[0] = new EDVGridAxis(fav.sourceName(), fav.destinationName(),
                fav.sourceAttributes(), fav.addAttributes(), newAxis0Values); 
              axisVariables[0].setActualRangeFromDestinationMinMax();
        }

        //make combined dataVariables
        int nDv = childStopsAt[nChildren - 1] + 1;
        dataVariables = new EDV[nDv];
        for (int c = 0; c < nChildren; c++) {
            int start = c == 0? 0 : childStopsAt[c - 1] + 1;
            System.arraycopy(childDatasets[c].dataVariables(), 0, dataVariables, start, 
                childDatasets[c].dataVariables().length);
        }

        //ensure the setup is valid
        ensureValid();

        //finally
        if (verbose) String2.log(
            (reallyVerbose? "\n" + toString() : "") +
            "\n*** EDDGridSideBySide " + datasetID + " constructor finished. TIME=" + 
            (System.currentTimeMillis() - constructionStartMillis) + "\n"); 

    }

    /**
     * Subclasses (like EDDGridFromDap) overwrite this to do a quick, 
     * incremental update of this dataset (i.e., for real time deal datasets).
     * 
     * <p>For simple failures, this writes into to log.txt but doesn't throw an exception.
     *
     * @throws Throwable if trouble. 
     * If the dataset has changed in a serious / incompatible way and needs a full
     * reload, this throws WaitThenTryAgainException 
     * (usually, catcher calls LoadDatasets.tryToUnload(...) and EDD.requestReloadASAP(tDatasetID))..
     */
    public void update() {
        //NOT FINISHED. NOT SIMPLE.
        //for (int i = 0; i < childDatasets.length; i++)
        //    childDatasets[i].update();
        //
        //rebuild indexOfAxis0Value 
        //protected IntArray indexOfAxis0Value[]; //an IntArray for each child; a row for each axis0 value
    }


    
    /** 
     * creationTimeMillis indicates when this dataset was created.
     * This overrides the EDD version in order to check if children need to be
     * reloaded.
     * 
     * @return when this dataset was created
     */
    public long creationTimeMillis() {
        //return the oldest creation time of this or of a child
        long tCTM = creationTimeMillis; 
        for (int c = 0; c < childDatasets.length; c++)
            tCTM = Math.min(tCTM, childDatasets[c].creationTimeMillis());
        return tCTM;
    }

    /**
     * This makes a sibling dataset, based on the new sourceUrl.
     *
     * @throws Throwable always (since this class doesn't support sibling())
     */
    public EDDGrid sibling(String tLocalSourceUrl, int ensureAxisValuesAreEqual, 
        boolean shareInfo) throws Throwable {
        throw new SimpleException("Error: " + 
            "EDDGridSideBySide doesn't support method=\"sibling\".");
    }

    /** 
     * This gets data (not yet standardized) from the data 
     * source for this EDDGrid.     
     * Because this is called by GridDataAccessor, the request won't be the 
     * full user's request, but will be a partial request (for less than
     * EDStatic.partialRequestMaxBytes).
     * 
     * @param tDataVariables
     * @param tConstraints
     * @return a PrimitiveArray[] where the first axisVariables.length elements
     *   are the axisValues and the next tDataVariables.length elements
     *   are the dataValues.
     *   Both the axisValues and dataValues are straight from the source,
     *   not modified.
     * @throws Throwable if trouble (notably, WaitThenTryAgainException)
     */
    public PrimitiveArray[] getSourceData(EDV tDataVariables[], IntArray tConstraints) 
        throws Throwable {

        //simple approach (not most efficient for tiny request, but fine for big requests):
        //  get results for each tDataVariable, one-by-one
        //future: more efficient to gang together all dataVariables from a given child
        int nAv = axisVariables.length;
        int nDv = dataVariables.length;
        int tnDv = tDataVariables.length;
        PrimitiveArray[] cumResults = new PrimitiveArray[nAv + tnDv];

        //get the array results 
        int nValues = 1, nValues1 = 1;
        for (int av = 0; av < nAv; av++) { 
            cumResults[av] = axisVariables[av].sourceValues().subset(
                tConstraints.get(av*3 + 0), 
                tConstraints.get(av*3 + 1), 
                tConstraints.get(av*3 + 2));
            nValues *= cumResults[av].size();
            if (av > 0)
                nValues1 *= cumResults[av].size();
        }

        //get the data results
        for (int tdv = 0; tdv < tnDv; tdv++) {

            //make a PrimitiveArray to hold the results for this dv
            PrimitiveArray dvResults = PrimitiveArray.factory(
                tDataVariables[tdv].sourceDataTypeClass(), nValues, false);
            cumResults[nAv + tdv] = dvResults;
            double tdvSourceMissingValue = tDataVariables[tdv].sourceMissingValue();

            //what is its dataVariable number in this aggregate dataset?
            //future: faster search with hash, but this is fast unless huge number of dataVars
            int dvn = 0;
            while (tDataVariables[tdv] != dataVariables[dvn])
                dvn++;

            //which childDataset is that in?
            int cn = 0;
            while (dvn > childStopsAt[cn])
                cn++;
            IntArray atIA = indexOfAxis0Value[cn];

            //step through constraints for combined axis0,
            //  finding sections in child of constant step size
            //!!!this is tricky code; think about it!!!
            IntArray ttConstraints = (IntArray)tConstraints.clone();
            int start = tConstraints.get(0);
            int stride = tConstraints.get(1);
            int stop = tConstraints.get(2);
            //String2.log("\n***sequence start=" + start + " stride=" + stride + " stop=" + stop);
            while (start <= stop) {
                //find first non-NaN
                while (start <= stop && atIA.array[start] == Integer.MAX_VALUE) {
                    dvResults.addNDoubles(nValues1, tdvSourceMissingValue);
                    start += stride;
                }
                if (start > stop)
                    break;

                //start value is valid
                //find as many more valid values as possible with constant stride for the child
                int cStart = atIA.array[start];
                int cStride = -1;
                int po = start + stride;
                while (po <= stop) { //go until value at po is trouble
                    int at = atIA.array[po];
                    if (at == Integer.MAX_VALUE) {
                        //String2.log("***sequence stopped because no corresponding av0 value for this child");
                        break;
                    }
                    if (cStride == -1) {
                        cStride = at - atIA.array[po - stride];
                    } else if (at - atIA.array[po - stride] != cStride) {
                        //String2.log("***sequence stopped because stride changed");
                        break;
                    }
                    po += stride;
                }

                //get the data
                if (cStride == -1)
                    cStride = 1;
                int cStop = atIA.array[po - stride]; //last valid value
                //String2.log("***sequence subsequence: cStart=" + cStart + " cStride=" + cStride + " cStop=" + cStop);
                ttConstraints.set(0, cStart);
                ttConstraints.set(1, cStride);
                ttConstraints.set(2, cStop);
                PrimitiveArray[] tResults = childDatasets[cn].getSourceData(
                    new EDV[]{tDataVariables[tdv]}, ttConstraints);
                dvResults.append(tResults[nAv]); //append the first (and only) data variable's results

                //increment start
                start = po;
            }

            //dvResults should be properly filled
            Test.ensureEqual(dvResults.size(), nValues, "Data source error in EDDGridSideBySide.getSourceData: " +
                "dvResults.size != nValues .");
        }

        return cumResults;
    }



    /**
     * This tests the methods in this class (easy test since x and y should have same time points).
     *
     * @param doGraphicsTests this is the only place that tests grid vector graphs.
     * @throws Throwable if trouble
     */
    public static void testQSWind(boolean doGraphicsTests) throws Throwable {

        String2.log("\n*** testQSWind");
        testVerboseOn();
        String name, tName, userDapQuery, results, expected, error;
        String dapQuery;

        //*** NDBC  is also IMPORTANT UNIQUE TEST of >1 variable in a file
        EDDGrid qsWind8 = (EDDGrid)oneFromDatasetXml("erdQSwind8day");

        //ensure that attributes are as expected
        Test.ensureEqual(qsWind8.combinedGlobalAttributes().getString("satellite"), "QuikSCAT", "");
        EDV edv = qsWind8.findDataVariableByDestinationName("x_wind");
        Test.ensureEqual(edv.combinedAttributes().getString("standard_name"), "x_wind", "");
        edv = qsWind8.findDataVariableByDestinationName("y_wind");
        Test.ensureEqual(edv.combinedAttributes().getString("standard_name"), "y_wind", "");
        Test.ensureEqual(qsWind8.combinedGlobalAttributes().getString("defaultGraphQuery"), "&.draw=vectors", "");

        //get data
        dapQuery = "x_wind[4:8][0][(-20)][(80)],y_wind[4:8][0][(-20)][(80)]";
        tName = qsWind8.makeNewFileForDapQuery(null, null, dapQuery, EDStatic.fullTestCacheDirectory, 
            qsWind8.className() + "1", ".csv"); 
        results = new String((new ByteArray(EDStatic.fullTestCacheDirectory + tName)).toArray());
        //String2.log(results);
        expected = 
/* pre 2010-07-19 was
"time, altitude, latitude, longitude, x_wind, y_wind\n" +
"UTC, m, degrees_north, degrees_east, m s-1, m s-1\n" +
"2000-02-06T00:00:00Z, 0.0, -20.0, 80.0, -8.639946, 5.16116\n" +
"2000-02-14T00:00:00Z, 0.0, -20.0, 80.0, -10.170271, 4.2629514\n" +
"2000-02-22T00:00:00Z, 0.0, -20.0, 80.0, -9.746282, 1.8640769\n" +
"2000-03-01T00:00:00Z, 0.0, -20.0, 80.0, -9.27684, 4.580559\n" +
"2000-03-09T00:00:00Z, 0.0, -20.0, 80.0, -5.0138364, 8.902227\n"; */
/* pre 2010-10-26 was
"time, altitude, latitude, longitude, x_wind, y_wind\n" +
"UTC, m, degrees_north, degrees_east, m s-1, m s-1\n" +
"1999-08-26T00:00:00Z, 0.0, -20.0, 80.0, -6.672732, 5.2165475\n" +
"1999-09-03T00:00:00Z, 0.0, -20.0, 80.0, -8.441742, 3.7559745\n" +
"1999-09-11T00:00:00Z, 0.0, -20.0, 80.0, -5.842872, 4.5936112\n" +
"1999-09-19T00:00:00Z, 0.0, -20.0, 80.0, -8.269525, 4.7751155\n" +
"1999-09-27T00:00:00Z, 0.0, -20.0, 80.0, -8.95586, 2.439596\n"; */
"time,altitude,latitude,longitude,x_wind,y_wind\n" +
"UTC,m,degrees_north,degrees_east,m s-1,m s-1\n" +
"1999-07-29T00:00:00Z,10.0,-20.0,80.0,-8.757242,4.1637316\n" +
"1999-07-30T00:00:00Z,10.0,-20.0,80.0,-9.012303,3.48984\n" +
"1999-07-31T00:00:00Z,10.0,-20.0,80.0,-8.631654,3.0311484\n" +
"1999-08-01T00:00:00Z,10.0,-20.0,80.0,-7.9840736,2.5528698\n" +
"1999-08-02T00:00:00Z,10.0,-20.0,80.0,-7.423252,2.432058\n";
        Test.ensureEqual(results, expected, "results=\n" + results);

        dapQuery = "x_wind[4:8][0][(-20)][(80)]";
        tName = qsWind8.makeNewFileForDapQuery(null, null, dapQuery, EDStatic.fullTestCacheDirectory, 
            qsWind8.className() + "2", ".csv"); 
        results = new String((new ByteArray(EDStatic.fullTestCacheDirectory + tName)).toArray());
        //String2.log(results);
        expected = 
/* pre 2010-10-26 was 
"time, altitude, latitude, longitude, x_wind\n" +
"UTC, m, degrees_north, degrees_east, m s-1\n" +
"1999-08-26T00:00:00Z, 0.0, -20.0, 80.0, -6.672732\n" +
"1999-09-03T00:00:00Z, 0.0, -20.0, 80.0, -8.441742\n" +
"1999-09-11T00:00:00Z, 0.0, -20.0, 80.0, -5.842872\n" +
"1999-09-19T00:00:00Z, 0.0, -20.0, 80.0, -8.269525\n" +
"1999-09-27T00:00:00Z, 0.0, -20.0, 80.0, -8.95586\n"; */
"time,altitude,latitude,longitude,x_wind\n" +
"UTC,m,degrees_north,degrees_east,m s-1\n" +
"1999-07-29T00:00:00Z,10.0,-20.0,80.0,-8.757242\n" +
"1999-07-30T00:00:00Z,10.0,-20.0,80.0,-9.012303\n" +
"1999-07-31T00:00:00Z,10.0,-20.0,80.0,-8.631654\n" +
"1999-08-01T00:00:00Z,10.0,-20.0,80.0,-7.9840736\n" +
"1999-08-02T00:00:00Z,10.0,-20.0,80.0,-7.423252\n";
        Test.ensureEqual(results, expected, "results=\n" + results);

        dapQuery = "y_wind[4:8][0][(-20)][(80)]";
        tName = qsWind8.makeNewFileForDapQuery(null, null, dapQuery, EDStatic.fullTestCacheDirectory, 
            qsWind8.className() + "3", ".csv"); 
        results = new String((new ByteArray(EDStatic.fullTestCacheDirectory + tName)).toArray());
        //String2.log(results);
        expected = 
/* pre 2010-10-26 was
"time, altitude, latitude, longitude, y_wind\n" +
"UTC, m, degrees_north, degrees_east, m s-1\n" +
"1999-08-26T00:00:00Z, 0.0, -20.0, 80.0, 5.2165475\n" +
"1999-09-03T00:00:00Z, 0.0, -20.0, 80.0, 3.7559745\n" +
"1999-09-11T00:00:00Z, 0.0, -20.0, 80.0, 4.5936112\n" +
"1999-09-19T00:00:00Z, 0.0, -20.0, 80.0, 4.7751155\n" +
"1999-09-27T00:00:00Z, 0.0, -20.0, 80.0, 2.439596\n"; */
"time,altitude,latitude,longitude,y_wind\n" +
"UTC,m,degrees_north,degrees_east,m s-1\n" +
"1999-07-29T00:00:00Z,10.0,-20.0,80.0,4.1637316\n" +
"1999-07-30T00:00:00Z,10.0,-20.0,80.0,3.48984\n" +
"1999-07-31T00:00:00Z,10.0,-20.0,80.0,3.0311484\n" +
"1999-08-01T00:00:00Z,10.0,-20.0,80.0,2.5528698\n" +
"1999-08-02T00:00:00Z,10.0,-20.0,80.0,2.432058\n";
        Test.ensureEqual(results, expected, "results=\n" + results);

        if (doGraphicsTests) {
            //graphics requests with no .specs 
            String2.log("\n****************** EDDGridSideBySide test get vector map\n");
            String vecDapQuery =  //minimal settings
                "x_wind[200][][(29):(50)][(225):(247)],y_wind[200][][(29):(50)][(225):(247)]"; 
            tName = qsWind8.makeNewFileForDapQuery(null, null, vecDapQuery, EDStatic.fullTestCacheDirectory, 
                qsWind8.className() + "_Vec1", ".png"); 
            SSR.displayInBrowser("file://" + EDStatic.fullTestCacheDirectory + tName);

            vecDapQuery =  //max settings
                "x_wind[200][][(29):(50)][(225):(247)],y_wind[200][][(29):(50)][(225):(247)]" +
                "&.color=0xFF9900&.font=1.25&.vec=10"; 
            tName = qsWind8.makeNewFileForDapQuery(null, null, vecDapQuery, EDStatic.fullTestCacheDirectory, 
                qsWind8.className() + "_Vec2", ".png"); 
            SSR.displayInBrowser("file://" + EDStatic.fullTestCacheDirectory + tName);

            //graphics requests with .specs -- lines
            tName = qsWind8.makeNewFileForDapQuery(null, null, 
                "x_wind[(2000-06-18Z):(2000-07-01Z)][(10.0)][(22.0)][(225.0)]" +
                "&.draw=lines&.vars=time|x_wind|&.color=0xFF9900",
                EDStatic.fullTestCacheDirectory, 
                qsWind8.className() + "_lines", ".png"); 
            SSR.displayInBrowser("file://" + EDStatic.fullTestCacheDirectory + tName);

            //linesAndMarkers              
            tName = qsWind8.makeNewFileForDapQuery(null, null, 
                "x_wind[(2000-06-18Z):(2000-07-01Z)][(10.0)][(22.0)][(225.0)]," +
                "y_wind[(2000-06-18Z):(2000-07-01Z)][(10.0)][(22.0)][(225.0)]" +
                "&.draw=linesAndMarkers&.vars=time|x_wind|y_wind&.marker=5|5&.color=0xFF9900&.colorBar=|C|Linear|||",
                EDStatic.fullTestCacheDirectory, 
                qsWind8.className() + "_linesAndMarkers", ".png"); 
            SSR.displayInBrowser("file://" + EDStatic.fullTestCacheDirectory + tName);

            //graphics requests with .specs -- markers              
            tName = qsWind8.makeNewFileForDapQuery(null, null, 
                "x_wind[(2000-06-18Z):(2000-07-01Z)][(10.0)][(22.0)][(225.0)]" +
                "&.draw=markers&.vars=time|x_wind|&.marker=1|5&.color=0xFF9900&.colorBar=|C|Linear|||",
                EDStatic.fullTestCacheDirectory, qsWind8.className() + "_markers", ".png"); 
            SSR.displayInBrowser("file://" + EDStatic.fullTestCacheDirectory + tName);

            //colored markers
            tName = qsWind8.makeNewFileForDapQuery(null, null, 
                "x_wind[(2000-06-18Z):(2000-07-01Z)][(10.0)][(22.0)][(225.0)]," +
                "y_wind[(2000-06-18Z):(2000-07-01Z)][(10.0)][(22.0)][(225.0)]" +
                "&.draw=markers&.vars=time|x_wind|y_wind&.marker=5|5&.colorBar=|C|Linear|||",
                EDStatic.fullTestCacheDirectory, qsWind8.className() + "_coloredMarkers", ".png"); 
            SSR.displayInBrowser("file://" + EDStatic.fullTestCacheDirectory + tName);

            //surface   
//needs 4 line legend
            tName = qsWind8.makeNewFileForDapQuery(null, null, 
                "x_wind[(2000-07-01Z)][(10.0)][(-75.0):(75.0)][(10.0):(360.0)]" +
                "&.draw=surface&.vars=longitude|latitude|x_wind&.colorBar=|C|Linear|||",
                EDStatic.fullTestCacheDirectory, qsWind8.className() + "_surface", ".png"); 
            SSR.displayInBrowser("file://" + EDStatic.fullTestCacheDirectory + tName);

            //sticks
            tName = qsWind8.makeNewFileForDapQuery(null, null, 
                "x_wind[(2000-06-24Z):(2000-07-01Z)][(10.0)][(75.0)][(360.0)]," +
                "y_wind[(2000-06-24Z):(2000-07-01Z)][(10.0)][(75.0)][(360.0)]" +
                "&.draw=sticks&.vars=time|x_wind|y_wind&.color=0xFF9900",
                EDStatic.fullTestCacheDirectory, qsWind8.className() + "_sticks", ".png"); 
            SSR.displayInBrowser("file://" + EDStatic.fullTestCacheDirectory + tName);

            //vectors
            tName = qsWind8.makeNewFileForDapQuery(null, null, 
                "x_wind[(2000-07-01Z)][(10.0)][(22.0):(50.0)][(225.0):(255.0)]," +
                "y_wind[(2000-07-01Z)][(10.0)][(22.0):(50.0)][(225.0):(255.0)]" +
                "&.draw=vectors&.vars=longitude|latitude|x_wind|y_wind&.color=0xFF9900",
                EDStatic.fullTestCacheDirectory, qsWind8.className() + "_vectors", ".png"); 
            SSR.displayInBrowser("file://" + EDStatic.fullTestCacheDirectory + tName);
        }
    }


    /**
     * The tests QSstress1day: datasets where children have different axis0 values.
     *
     * @throws Throwable if trouble
     */
    public static void testQSStress() throws Throwable {
        String2.log("\n*** testQSWind");
        testVerboseOn();
        String name, tName, userDapQuery, results, expected, error;
        String dapQuery;
        Test.ensureEqual(Calendar2.epochSecondsToIsoStringT(1.1306736E9), "2005-10-30T12:00:00", "");

        EDDGrid qs1 = (EDDGrid)oneFromDatasetXml("erdQSstress1day");
        dapQuery = "taux[(1.130328E9):(1.1309328E9)][0][(-20)][(40)],tauy[(1.130328E9):(1.1309328E9)][0][(-20)][(40)]";
        tName = qs1.makeNewFileForDapQuery(null, null, dapQuery, EDStatic.fullTestCacheDirectory, 
            qs1.className() + "sbsxy", ".csv"); 
        results = new String((new ByteArray(EDStatic.fullTestCacheDirectory + tName)).toArray());
        //String2.log(results);
        expected = 
"time,altitude,latitude,longitude,taux,tauy\n" +
"UTC,m,degrees_north,degrees_east,Pa,Pa\n" +
"2005-10-26T12:00:00Z,0.0,-20.0,40.0,-0.0204974,0.0194982\n" +
"2005-10-27T12:00:00Z,0.0,-20.0,40.0,-0.08357,0.155034\n" +
"2005-10-28T12:00:00Z,0.0,-20.0,40.0,-0.0173238,0.0646148\n" +
"2005-10-29T12:00:00Z,0.0,-20.0,40.0,-0.0175401,0.0921468\n" +
"2005-10-30T12:00:00Z,0.0,-20.0,40.0,-0.0110465,0.121438\n" +
"2005-10-31T12:00:00Z,0.0,-20.0,40.0,-3.6782E-4,0.0166592\n" +
"2005-11-01T12:00:00Z,0.0,-20.0,40.0,-0.0040105,0.0141265\n" +
"2005-11-02T12:00:00Z,0.0,-20.0,40.0,-0.0481124,-0.0946055\n";
        Test.ensureEqual(results, expected, "results=\n" + results);
        
        dapQuery = "taux[(1.130328E9):2:(1.1309328E9)][0][(-20)][(40)],tauy[(1.130328E9):2:(1.1309328E9)][0][(-20)][(40)]";
        tName = qs1.makeNewFileForDapQuery(null, null, dapQuery, EDStatic.fullTestCacheDirectory, 
            qs1.className() + "sbsxy2a", ".csv"); 
        results = new String((new ByteArray(EDStatic.fullTestCacheDirectory + tName)).toArray());
        //String2.log(results);
        expected = 
"time,altitude,latitude,longitude,taux,tauy\n" +
"UTC,m,degrees_north,degrees_east,Pa,Pa\n" +
"2005-10-26T12:00:00Z,0.0,-20.0,40.0,-0.0204974,0.0194982\n" +
"2005-10-28T12:00:00Z,0.0,-20.0,40.0,-0.0173238,0.0646148\n" +
"2005-10-30T12:00:00Z,0.0,-20.0,40.0,-0.0110465,0.121438\n" +
"2005-11-01T12:00:00Z,0.0,-20.0,40.0,-0.0040105,0.0141265\n";
        Test.ensureEqual(results, expected, "results=\n" + results);
        
        dapQuery = "taux[(2005-10-27T12:00:00Z):2:(1.1309328E9)][0][(-20)][(40)],tauy[(2005-10-27T12:00:00Z):2:(1.1309328E9)][0][(-20)][(40)]";
        tName = qs1.makeNewFileForDapQuery(null, null, dapQuery, EDStatic.fullTestCacheDirectory, 
            qs1.className() + "sbsxy2b", ".csv"); 
        results = new String((new ByteArray(EDStatic.fullTestCacheDirectory + tName)).toArray());
        //String2.log(results);
        expected = 
"time,altitude,latitude,longitude,taux,tauy\n" +
"UTC,m,degrees_north,degrees_east,Pa,Pa\n" +
"2005-10-27T12:00:00Z,0.0,-20.0,40.0,-0.08357,0.155034\n" +
"2005-10-29T12:00:00Z,0.0,-20.0,40.0,-0.0175401,0.0921468\n" +
"2005-10-31T12:00:00Z,0.0,-20.0,40.0,-3.6782E-4,0.0166592\n" +
"2005-11-02T12:00:00Z,0.0,-20.0,40.0,-0.0481124,-0.0946055\n";
        Test.ensureEqual(results, expected, "results=\n" + results);

        //test missing time point represents >1 missing value
        dapQuery = "taux[(1.130328E9):2:(1.1309328E9)][0][(-20)][(40):(40.5)],tauy[(1.130328E9):2:(1.1309328E9)][0][(-20)][(40):(40.5)]";
        tName = qs1.makeNewFileForDapQuery(null, null, dapQuery, EDStatic.fullTestCacheDirectory, 
            qs1.className() + "sbsxy2c", ".csv"); 
        results = new String((new ByteArray(EDStatic.fullTestCacheDirectory + tName)).toArray());
        //String2.log(results);
        expected = 
"time,altitude,latitude,longitude,taux,tauy\n" +
"UTC,m,degrees_north,degrees_east,Pa,Pa\n" +
"2005-10-26T12:00:00Z,0.0,-20.0,40.0,-0.0204974,0.0194982\n" +
"2005-10-26T12:00:00Z,0.0,-20.0,40.125,-0.013397,0.0241179\n" +
"2005-10-26T12:00:00Z,0.0,-20.0,40.25,-0.0073588,0.0304036\n" +
"2005-10-26T12:00:00Z,0.0,-20.0,40.375,-0.017018,0.0420586\n" +
"2005-10-26T12:00:00Z,0.0,-20.0,40.5,0.0074961,0.0470117\n" +
"2005-10-28T12:00:00Z,0.0,-20.0,40.0,-0.0173238,0.0646148\n" +
"2005-10-28T12:00:00Z,0.0,-20.0,40.125,-0.0345203,0.0789654\n" +
"2005-10-28T12:00:00Z,0.0,-20.0,40.25,-0.0363615,0.115381\n" +
"2005-10-28T12:00:00Z,0.0,-20.0,40.375,-0.0537272,0.133791\n" +
"2005-10-28T12:00:00Z,0.0,-20.0,40.5,-0.0626239,0.133823\n" +
"2005-10-30T12:00:00Z,0.0,-20.0,40.0,-0.0110465,0.121438\n" +
"2005-10-30T12:00:00Z,0.0,-20.0,40.125,-0.00235985,0.137055\n" +
"2005-10-30T12:00:00Z,0.0,-20.0,40.25,-0.0088645,0.137093\n" +
"2005-10-30T12:00:00Z,0.0,-20.0,40.375,-0.0196206,0.135083\n" +
"2005-10-30T12:00:00Z,0.0,-20.0,40.5,-0.044424,0.160418\n" +
"2005-11-01T12:00:00Z,0.0,-20.0,40.0,-0.0040105,0.0141265\n" +
"2005-11-01T12:00:00Z,0.0,-20.0,40.125,-0.00600515,0.0118363\n" +
"2005-11-01T12:00:00Z,0.0,-20.0,40.25,-0.010433,0.0160705\n" +
"2005-11-01T12:00:00Z,0.0,-20.0,40.375,-0.0243944,0.021086\n" +
"2005-11-01T12:00:00Z,0.0,-20.0,40.5,-0.0408028,0.028469\n";
        Test.ensureEqual(results, expected, "results=\n" + results);   
    }

    /**
     * Some one time tests of this class.
     */
    public static void testOneTime() throws Throwable {

        //one time test of separate taux and tauy datasets datasets
        //lowestValue=1.130328E9 290 292
        //lowestValue=1.1304144E9 291 293
        //lowestValue=1.1305008E9 292 294
        //lowestValue=1.1305872E9 293 295
        //lowestValue=1.1306736E9 294 NaN
        //lowestValue=1.13076E9 295 296
        //lowestValue=1.1308464E9 296 297
        //lowestValue=1.1309328E9 297 298

        /* not active; needs work
        EDDGrid qsx1 = (EDDGrid)oneFromDatasetXml("erdQStaux1day");
        dapQuery = "taux[(1.130328E9):(1.1309328E9)][0][(-20)][(40)]";
        tName = qsx1.makeNewFileForDapQuery(null, null, dapQuery, EDStatic.fullTestCacheDirectory, 
            qsz1.className() + "sbsx", ".csv"); 
        results = new String((new ByteArray(EDStatic.fullTestCacheDirectory + tName)).toArray());
        //String2.log(results);
        expected = 
"time,altitude,latitude,longitude,taux\n" +
"UTC,m,degrees_north,degrees_east,Pa\n" +
"2005-10-26T12:00:00Z,0.0,-20.0,40.0,-0.0102223\n" +
"2005-10-27T12:00:00Z,0.0,-20.0,40.0,-0.0444894\n" +
"2005-10-28T12:00:00Z,0.0,-20.0,40.0,-0.025208\n" +
"2005-10-29T12:00:00Z,0.0,-20.0,40.0,-0.0156589\n" +
"2005-10-30T12:00:00Z,0.0,-20.0,40.0,-0.0345074\n" +
"2005-10-31T12:00:00Z,0.0,-20.0,40.0,-0.00270854\n" +
"2005-11-01T12:00:00Z,0.0,-20.0,40.0,-0.00875408\n" +
"2005-11-02T12:00:00Z,0.0,-20.0,40.0,-0.0597476\n";
        Test.ensureEqual(results, expected, "results=\n" + results);

        EDDGrid qsy1 = (EDDGrid)oneFromDatasetXml("erdQStauy1day");
        dapQuery = "tauy[(1.130328E9):(1.1309328E9)][0][(-20)][(40)]";
        tName = qsy1.makeNewFileForDapQuery(null, null, dapQuery, EDStatic.fullTestCacheDirectory, 
            qsy1.className() + "sbsy", ".csv"); 
        results = new String((new ByteArray(EDStatic.fullTestCacheDirectory + tName)).toArray());
        //String2.log(results);
        expected = 
"results=time,altitude,latitude,longitude,tauy\n" +
"UTC,m,degrees_north,degrees_east,Pa\n" +
"2005-10-26T12:00:00Z,0.0,-20.0,40.0,0.0271539\n" +
"2005-10-27T12:00:00Z,0.0,-20.0,40.0,0.180277\n" +
"2005-10-28T12:00:00Z,0.0,-20.0,40.0,0.0779955\n" +
"2005-10-29T12:00:00Z,0.0,-20.0,40.0,0.0994889\n" + 
//note no 10-30 data
"2005-10-31T12:00:00Z,0.0,-20.0,40.0,0.0184601\n" +
"2005-11-01T12:00:00Z,0.0,-20.0,40.0,0.0145052\n" +
"2005-11-02T12:00:00Z,0.0,-20.0,40.0,-0.0942029\n";
        Test.ensureEqual(results, expected, "results=\n" + results);
        */


 /*
        //test creation of sideBySide datasets
        EDDGrid ta1  = (EDDGrid)oneFromDatasetXml("erdTAgeo1day");
        EDDGrid j110 = (EDDGrid)oneFromDatasetXml("erdJ1geo10day");
        EDDGrid tas  = (EDDGrid)oneFromDatasetXml("erdTAssh1day");

        EDDGrid qs1  = (EDDGrid)oneFromDatasetXml("erdQSwind1day");
        EDDGrid qs3  = (EDDGrid)oneFromDatasetXml("erdQSwind3day");
        EDDGrid qs4  = (EDDGrid)oneFromDatasetXml("erdQSwind4day");       
        EDDGrid qs8  = (EDDGrid)oneFromDatasetXml("erdQSwind8day");
        EDDGrid qs14 = (EDDGrid)oneFromDatasetXml("erdQSwind14day");
        EDDGrid qsm  = (EDDGrid)oneFromDatasetXml("erdQSwindmday");

        EDDGrid qss1  = (EDDGrid)oneFromDatasetXml("erdQSstress1day");
        EDDGrid qss3  = (EDDGrid)oneFromDatasetXml("erdQSstress3day");
        EDDGrid qss8  = (EDDGrid)oneFromDatasetXml("erdQSstress8day");
        EDDGrid qss14 = (EDDGrid)oneFromDatasetXml("erdQSstress14day");
        EDDGrid qssm  = (EDDGrid)oneFromDatasetXml("erdQSstressmday");

        EDDGrid qse7  = (EDDGrid)oneFromDatasetXml("erdQSekm7day");
        EDDGrid qse8  = (EDDGrid)oneFromDatasetXml("erdQSekm8day");
// */  


/* 
        //test if datasets can be aggregated side-by-side
        EDDGrid n1Children[] = new EDDGrid[]{
            (EDDGrid)oneFromDatasetXml("erdCAusfchday"),
            (EDDGrid)oneFromDatasetXml("erdCAvsfchday"),
            (EDDGrid)oneFromDatasetXml("erdQSumod1day"),
            (EDDGrid)oneFromDatasetXml("erdQStmod1day"),
            (EDDGrid)oneFromDatasetXml("erdQScurl1day"),
            (EDDGrid)oneFromDatasetXml("erdQStaux1day"),
            (EDDGrid)oneFromDatasetXml("erdQStauy1day")
            };
        new EDDGridSideBySide("erdQSwind1day", n1Children);
        */
    }

    /** This test making transparentPngs.
     */
    public static void testTransparentPng() throws Throwable {
        String2.log("\n*** testTransparentPng");
        testVerboseOn();
        String dir = EDStatic.fullTestCacheDirectory;
        String name, tName, userDapQuery, results, expected, error;
        String dapQuery;

        EDDGrid qsWind8 = (EDDGrid)oneFromDatasetXml("erdQSwind8day");
/* */

        //surface  map
        dapQuery = 
            "x_wind[0][][][]" +
            "&.draw=surface&.vars=longitude|latitude|x_wind";  
        tName = qsWind8.makeNewFileForDapQuery(null, null, dapQuery, 
            dir, qsWind8.className() + "_surface", ".transparentPng"); 
        SSR.displayInBrowser("file://" + dir + tName);
        tName = qsWind8.makeNewFileForDapQuery(null, null, dapQuery + "&.size=360|150", 
            dir, qsWind8.className() + "_surface360150", ".transparentPng"); 
        SSR.displayInBrowser("file://" + dir + tName);

        //vector  map
        dapQuery = 
            "x_wind[0][][][]," +
            "y_wind[0][][][]" +
            "&.draw=vectors&.vars=longitude|latitude|x_wind|y_wind&.color=0xff0000";
        tName = qsWind8.makeNewFileForDapQuery(null, null, dapQuery, 
            dir, qsWind8.className() + "_vectors", ".transparentPng"); 
        SSR.displayInBrowser("file://" + dir + tName);
        tName = qsWind8.makeNewFileForDapQuery(null, null, dapQuery + "&.size=360|150", 
            dir, qsWind8.className() + "_vectors360150", ".transparentPng"); 
        SSR.displayInBrowser("file://" + dir + tName);

        //lines on a graph
        dapQuery = 
            "x_wind[0:20][0][300][0]" +
            "&.draw=lines&.vars=time|x_wind&.color=0xff0000";
        tName = qsWind8.makeNewFileForDapQuery(null, null, dapQuery, 
            dir, qsWind8.className() + "_lines", ".transparentPng"); 
        SSR.displayInBrowser("file://" + dir + tName);
        tName = qsWind8.makeNewFileForDapQuery(null, null, dapQuery + "&.size=500|400", 
            dir, qsWind8.className() + "_lines500400", ".transparentPng"); 
        SSR.displayInBrowser("file://" + dir + tName);

        //markers on a graph
        dapQuery = 
            "x_wind[0:20][0][300][0]" +
            "&.draw=markers&.vars=time|x_wind&.color=0xff0000";
        tName = qsWind8.makeNewFileForDapQuery(null, null, dapQuery, 
            dir, qsWind8.className() + "_markers",  ".transparentPng"); 
        SSR.displayInBrowser("file://" + dir + tName);
        tName = qsWind8.makeNewFileForDapQuery(null, null, dapQuery + "&.size=500|400", 
            dir, qsWind8.className() + "_markers500400",  ".transparentPng"); 
        SSR.displayInBrowser("file://" + dir + tName);

        //sticks on a graph
        dapQuery = 
            "x_wind[0:20][0][300][0]," +
            "y_wind[0:20][0][300][0]" +
            "&.draw=sticks&.vars=time|x_wind|y_wind&.color=0xff0000";
        tName = qsWind8.makeNewFileForDapQuery(null, null, dapQuery, 
            dir, qsWind8.className() + "_sticks", ".transparentPng"); 
        SSR.displayInBrowser("file://" + dir + tName);
        tName = qsWind8.makeNewFileForDapQuery(null, null, dapQuery + "&.size=500|500", 
            dir, qsWind8.className() + "_sticks500500", ".transparentPng"); 
        SSR.displayInBrowser("file://" + dir + tName);
/* */
    }

    /**
     * This tests the methods in this class.
     *
     * @param doGraphicsTests this is the only place that tests grid vector graphs.
     * @throws Throwable if trouble
     */
    public static void test(boolean doGraphicsTests) throws Throwable {

        String2.log("\n****************** EDDGridSideBySide.test() *****************\n");

        //usually done
        testQSWind(doGraphicsTests); 
        testQSStress();
        testTransparentPng();

        //usually not done
        //testOneTime();


        String2.log("\n*** EDDGridSideBySide.test finished.");
    }
}
