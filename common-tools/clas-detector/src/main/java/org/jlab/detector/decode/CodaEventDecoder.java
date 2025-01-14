package org.jlab.detector.decode;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jlab.coda.jevio.ByteDataTransformer;
import org.jlab.coda.jevio.CompositeData;
import org.jlab.coda.jevio.DataType;
import org.jlab.coda.jevio.EvioException;
import org.jlab.coda.jevio.EvioNode;
import org.jlab.detector.decode.DetectorDataDgtz.ADCData;
import org.jlab.detector.decode.DetectorDataDgtz.HelicityDecoderData;
import org.jlab.detector.decode.DetectorDataDgtz.SCALERData;
import org.jlab.detector.decode.DetectorDataDgtz.TDCData;
import org.jlab.detector.decode.DetectorDataDgtz.VTPData;
import org.jlab.detector.helicity.HelicityBit;
import org.jlab.io.evio.EvioDataEvent;
import org.jlab.io.evio.EvioSource;
import org.jlab.io.evio.EvioTreeBranch;
import org.jlab.utils.data.DataUtils;

import org.jlab.jnp.utils.json.JsonObject;

/**
 *
 * @author gavalian
 */
public class CodaEventDecoder {

    private int runNumber = 0;
    private int eventNumber = 0;
    private int unixTime = 0;
    private long timeStamp = 0L;
    private int timeStampErrors = 0;
    private long triggerBits = 0;
    private byte helicityLevel3 = HelicityBit.UDF.value();
    private List<Integer> triggerWords = new ArrayList<>();
    JsonObject epicsData = new JsonObject();

    private final long timeStampTolerance = 0L;
    private int tiMaster = -1;

    /* 
    * From the maroc we get slot 0, 1, 2 etc, but in this crate the uRwell also has slots 0 to 15
    * We will add an offset value 20 for this data so the TT will be unique.
     */
    private final byte HodoSlotOffset = 20;

    public CodaEventDecoder() {

    }

    /**
     * returns detector digitized data entries from the event. all branches are
     * analyzed and different types of digitized data is created for each type
     * of ADC and TDC data.
     *
     * @param event
     * @return
     */
    public List<DetectorDataDgtz> getDataEntries(EvioDataEvent event) {

        //int event_size = event.getHandler().getStructure().getByteBuffer().array().length;
        // This had been inserted to accommodate large EVIO events that
        // were unreadable in JEVIO versions prior to 6.2:
        //if(event_size>600*1024){
        //    System.out.println("error: >>>> EVENT SIZE EXCEEDS 600 kB");
        //    return new ArrayList<DetectorDataDgtz>();
        //}
        // zero out the trigger bits, but let the others properties inherit
        // from the previous event, in the case where there's no HEAD bank:
        this.setTriggerBits(0);

        List<DetectorDataDgtz> rawEntries = new ArrayList<DetectorDataDgtz>();
        List<EvioTreeBranch> branches = this.getEventBranches(event);
        this.setTimeStamp(event);
        for (EvioTreeBranch branch : branches) {

            List<DetectorDataDgtz> list = this.getDataEntries(event, branch.getTag());
            if (list != null) {
                rawEntries.addAll(list);
            }
        }
        List<DetectorDataDgtz> tdcEntries = this.getDataEntries_TDC(event);
        rawEntries.addAll(tdcEntries);
        List<DetectorDataDgtz> vtpEntries = this.getDataEntries_VTP(event);
        rawEntries.addAll(vtpEntries);
        List<DetectorDataDgtz> scalerEntries = this.getDataEntries_Scalers(event);
        rawEntries.addAll(scalerEntries);

        this.getDataEntries_EPICS(event);
        this.getDataEntries_HelicityDecoder(event);

        return rawEntries;
    }

    public JsonObject getEpicsData() {
        return this.epicsData;
    }

    public List<Integer> getTriggerWords() {
        return this.triggerWords;
    }

    private void printByteBuffer(ByteBuffer buffer, int max, int columns) {
        int n = max;
        if (buffer.capacity() < max) {
            n = buffer.capacity();
        }
        StringBuilder str = new StringBuilder();
        for (int i = 0; i < n; i++) {
            str.append(String.format("%02X ", buffer.get(i)));
            if ((i + 1) % columns == 0) {
                str.append("\n");
            }
        }
        System.out.println(str.toString());
    }

    public int getRunNumber() {
        return this.runNumber;
    }

    public int getEventNumber() {
        return this.eventNumber;
    }

    public int getUnixTime() {
        return this.unixTime;
    }

    public long getTimeStamp() {
        return this.timeStamp;
    }

    public byte getHelicityLevel3() {
        return this.helicityLevel3;
    }

    public void setTimeStamp(EvioDataEvent event) {

        long ts = -1;

        List<DetectorDataDgtz> tiEntries = this.getDataEntries_TI(event);

        if (tiEntries.size() == 1) {
            ts = tiEntries.get(0).getTimeStamp();
        } else if (tiEntries.size() > 1) {
            // check sychronization
            boolean tiSync = true;
            int i0 = -1;
            // set reference timestamp from first entry which is not the tiMaster
            for (int i = 0; i < tiEntries.size(); i++) {
                if (tiEntries.get(i).getDescriptor().getCrate() != this.tiMaster) {
                    i0 = i;
                    break;
                }
            }
            for (int i = 0; i < tiEntries.size(); i++) {
                long deltaTS = this.timeStampTolerance;
                if (tiEntries.get(i).getDescriptor().getCrate() == this.tiMaster) {
                    deltaTS = deltaTS + 1;  // add 1 click tolerance for tiMaster
                }
                if (Math.abs(tiEntries.get(i).getTimeStamp() - tiEntries.get(i0).getTimeStamp()) > deltaTS) {
                    tiSync = false;
                    if (this.timeStampErrors < 100) {
                        System.err.println("WARNING: mismatch in TI time stamps: crate "
                                + tiEntries.get(i).getDescriptor().getCrate() + " reports "
                                + tiEntries.get(i).getTimeStamp() + " instead of the " + ts
                                + " from crate " + tiEntries.get(i0).getDescriptor().getCrate());
                    } else if (this.timeStampErrors == 100) {
                        System.err.println("WARNING: reached the maximum number of timeStamp errors (100), supressing future warnings.");
                    }
                    this.timeStampErrors++;
                }
            }
            if (tiSync) {
                ts = tiEntries.get(i0).getTimeStamp();
            }
        }
        this.timeStamp = ts;
    }

    public long getTriggerBits() {
        return triggerBits;
    }

    public void setTriggerBits(long triggerBits) {
        this.triggerBits = triggerBits;
    }

    public List<FADCData> getADCEntries(EvioDataEvent event) {
        List<FADCData> entries = new ArrayList<>();
        List<EvioTreeBranch> branches = this.getEventBranches(event);
        for (EvioTreeBranch branch : branches) {
            List<FADCData> list = this.getADCEntries(event, branch.getTag());
            if (list != null) {
                entries.addAll(list);
            }
        }
        return entries;
    }

    public List<FADCData> getADCEntries(EvioDataEvent event, int crate) {
        List<FADCData> entries = new ArrayList<>();

        List<EvioTreeBranch> branches = this.getEventBranches(event);
        EvioTreeBranch cbranch = this.getEventBranch(branches, crate);

        if (cbranch == null) {
            return null;
        }

        for (EvioNode node : cbranch.getNodes()) {
            if (node.getTag() == 57638) {
                return this.getDataEntries_57638(crate, node, event);
            }
        }

        return entries;
    }

    public List<FADCData> getADCEntries(EvioDataEvent event, int crate, int tagid) {

        List<FADCData> adc = new ArrayList<>();
        List<EvioTreeBranch> branches = this.getEventBranches(event);

        EvioTreeBranch cbranch = this.getEventBranch(branches, crate);
        if (cbranch == null) {
            return null;
        }

        for (EvioNode node : cbranch.getNodes()) {
            if (node.getTag() == tagid) {
                //  This is regular integrated pulse mode, used for FTOF
                // FTCAL and EC/PCAL
                return this.getADCEntries_Tag(crate, node, event, tagid);
            }
        }
        return adc;
    }

    /**
     * returns list of decoded data in the event for given crate.
     *
     * @param event
     * @param crate
     * @return
     */
    public List<DetectorDataDgtz> getDataEntries(EvioDataEvent event, int crate) {

        List<EvioTreeBranch> branches = this.getEventBranches(event);
        List<DetectorDataDgtz> bankEntries = new ArrayList<>();

        EvioTreeBranch cbranch = this.getEventBranch(branches, crate);
        if (cbranch == null) {
            return null;
        }

        for (EvioNode node : cbranch.getNodes()) {
            if (node.getTag() == 57615) {
                //  This is regular integrated pulse mode, used for FTOF
                // FTCAL and EC/PCAL
                this.tiMaster = crate;
                this.readHeaderBank(crate, node, event);
            }
        }

        List<DetectorDataDgtz> combineList = new ArrayList<>();
        for (EvioNode node : cbranch.getNodes()) {

            //  XY Hodoscope TDC data
            if (node.getTag() == 57655) {
                combineList.addAll(this.getDataEntries_57655(crate, node, event));
            } else if (node.getTag() == 57631) {
                combineList.addAll(this.getDataEntries_57631(crate, node, event));
            } else if (node.getTag() == 57653) {
                combineList.addAll(this.getDataEntries_57653(crate, node, event));
            }

        }
        if (!combineList.isEmpty()) {
            return combineList;
        }

        for (EvioNode node : cbranch.getNodes()) {

            if (node.getTag() == 57617) {
                //  This is regular integrated pulse mode, used for FTOF
                // FTCAL and EC/PCAL
                return this.getDataEntries_57617(crate, node, event);
            } else if (node.getTag() == 57602) {
                //  This is regular integrated pulse mode, used for FTOF
                // FTCAL and EC/PCAL
                return this.getDataEntries_57602(crate, node, event);
            } else if (node.getTag() == 57601) {
                //  This is regular integrated pulse mode, used for FTOF
                // FTCAL and EC/PCAL
                return this.getDataEntries_57601(crate, node, event);
            } else if (node.getTag() == 57627) {
                //  This is regular integrated pulse mode, used for MM
                return this.getDataEntries_57627(crate, node, event);
            } else if (node.getTag() == 57640) {
                //  This is bit-packed pulse mode, used for MM
                return this.getDataEntries_57640(crate, node, event);
            } else if (node.getTag() == 57622) {
                //  This is regular integrated pulse mode, used for FTOF
                // FTCAL and EC/PCAL
                return this.getDataEntries_57622(crate, node, event);
            } else if (node.getTag() == 57636) {
                //  RICH TDC data
                return this.getDataEntries_57636(crate, node, event);
            } else if (node.getTag() == 57641) {
                //  RTPC  data decoding
                return this.getDataEntries_57641(crate, node, event);
            }/* else if (node.getTag() == 57631) {
                //  SRS-APV data decoding
                return this.getDataEntries_57631(crate, node, event);
            }*/
        }
        return bankEntries;
    }

    /**
     * Returns an array of the branches in the event.
     *
     * @param event
     * @return
     */
    public List<EvioTreeBranch> getEventBranches(EvioDataEvent event) {
        ArrayList<EvioTreeBranch> branches = new ArrayList<>();
        try {

            List<EvioNode> eventNodes = event.getStructureHandler().getNodes();
            if (eventNodes == null) {
                return branches;
            }

            for (EvioNode node : eventNodes) {
                EvioTreeBranch eBranch = new EvioTreeBranch(node.getTag(), node.getNum());
                List<EvioNode> childNodes = node.getChildNodes();
                if (childNodes != null) {
                    for (EvioNode child : childNodes) {
                        eBranch.addNode(child);
                    }
                    branches.add(eBranch);
                }
            }

        } catch (EvioException ex) {
            Logger.getLogger(CodaEventDecoder.class.getName()).log(Level.SEVERE, null, ex);
        }
        return branches;
    }

    /**
     * returns branch with with given tag
     *
     * @param branches
     * @param tag
     * @return
     */
    public EvioTreeBranch getEventBranch(List<EvioTreeBranch> branches, int tag) {
        for (EvioTreeBranch branch : branches) {
            if (branch.getTag() == tag) {
                return branch;
            }
        }
        return null;
    }

    public void readHeaderBank(Integer crate, EvioNode node, EvioDataEvent event) {

        if (node.getDataTypeObj() == DataType.INT32 || node.getDataTypeObj() == DataType.UINT32) {
            try {
                int[] intData = ByteDataTransformer.toIntArray(node.getStructureBuffer(true));
                this.runNumber = intData[3];
                this.eventNumber = intData[4];
                if (intData[5] != 0) {
                    this.unixTime = intData[5];
                }
                this.helicityLevel3 = HelicityBit.DNE.value();
                if (intData.length > 7) {
                    if ((intData[7] & 0x1) == 0) {
                        this.helicityLevel3 = HelicityBit.UDF.value();
                    } else if ((intData[7] >> 1 & 0x1) == 0) {
                        this.helicityLevel3 = HelicityBit.MINUS.value();
                    } else {
                        this.helicityLevel3 = HelicityBit.PLUS.value();
                    }
                }
            } catch (Exception e) {
                this.runNumber = 10;
                this.eventNumber = 1;
            }
        } else {
            System.out.println("[error] can not read header bank");
        }
    }

    /**
     * SVT decoding
     *
     * @param crate
     * @param node
     * @param event
     * @return
     */
    public ArrayList<DetectorDataDgtz> getDataEntries_57617(Integer crate, EvioNode node, EvioDataEvent event) {

        ArrayList<DetectorDataDgtz> rawdata = new ArrayList<>();

        if (node.getTag() == 57617) {
            try {
                ByteBuffer compBuffer = node.getByteData(true);
                CompositeData compData = new CompositeData(compBuffer.array(), event.getByteOrder());
                List<Object> cdataitems = compData.getItems();
                int totalSize = cdataitems.size();
                int position = 0;
                while ((position + 4) < totalSize) {
                    Byte slot = (Byte) cdataitems.get(position);
                    //Integer trig = (Integer)  cdataitems.get(position+1);
                    Long time = (Long) cdataitems.get(position + 2);
                    Integer nchannels = (Integer) cdataitems.get(position + 3);
                    int counter = 0;
                    position = position + 4;
                    while (counter < nchannels) {
                        Byte half = (Byte) cdataitems.get(position);
                        Byte channel = (Byte) cdataitems.get(position + 1);
                        Byte tdcbyte = (Byte) cdataitems.get(position + 2);
                        Short tdc = DataUtils.getShortFromByte(tdcbyte);
                        Byte adcbyte = (Byte) cdataitems.get(position + 3);

                        // regular FSSR data entry
                        int halfWord = DataUtils.getIntFromByte(half);
                        int chipID = DataUtils.getInteger(halfWord, 0, 2);
                        int halfID = DataUtils.getInteger(halfWord, 3, 3);
                        int adc = adcbyte;
                        //Integer channelKey = ((half<<8) | (channel & 0xff));

                        // TDC data entry
                        if (half == -128) {
                            halfWord = DataUtils.getIntFromByte(channel);
                            halfID = DataUtils.getInteger(halfWord, 2, 2);
                            chipID = DataUtils.getInteger(halfWord, 0, 1) + 1;
                            channel = 0;
                            //channelKey = 0;
                            tdc = (short) ((adcbyte << 8) | (tdcbyte & 0xff));
                            adc = -1;
                        }

                        int channelID = halfID * 10000 + chipID * 1000 + channel;
                        position += 4;
                        counter++;
                        DetectorDataDgtz entry = new DetectorDataDgtz(crate, slot, channelID);
                        ADCData adcData = new ADCData();
                        adcData.setIntegral(adc);
                        adcData.setPedestal((short) 0);
                        adcData.setADC(0, 0);
                        adcData.setTime(tdc);
                        adcData.setTimeStamp(time);
                        entry.addADC(adcData);
                        rawdata.add(entry);
                    }
                }

            } catch (EvioException ex) {
                //Logger.getLogger(EvioRawDataSource.class.getName()).log(Level.SEVERE, null, ex);
            } catch (IndexOutOfBoundsException ex) {
                //System.out.println("[ERROR] ----> ERROR DECODING COMPOSITE DATA FOR ONE EVENT");
            }
        }
        return rawdata;
    }

    public List<FADCData> getADCEntries_Tag(Integer crate, EvioNode node, EvioDataEvent event, int tagid) {
        List<FADCData> entries = new ArrayList<>();
        if (node.getTag() == tagid) {
            try {

                ByteBuffer compBuffer = node.getByteData(true);
                CompositeData compData = new CompositeData(compBuffer.array(), event.getByteOrder());

                List<DataType> cdatatypes = compData.getTypes();
                List<Object> cdataitems = compData.getItems();

                if (cdatatypes.get(3) != DataType.NVALUE) {
                    System.err.println("[EvioRawDataSource] ** error ** corrupted "
                            + " bank. tag = " + node.getTag() + " num = " + node.getNum());
                    return null;
                }

                int position = 0;

                while (position < cdatatypes.size() - 4) {
                    Byte slot = (Byte) cdataitems.get(position + 0);
                    //Integer trig = (Integer)  cdataitems.get(position+1);
                    //Long    time = (Long)     cdataitems.get(position+2);

                    Integer nchannels = (Integer) cdataitems.get(position + 3);
                    position += 4;
                    int counter = 0;
                    while (counter < nchannels) {
                        Byte channel = (Byte) cdataitems.get(position);
                        Integer length = (Integer) cdataitems.get(position + 1);
                        FADCData bank = new FADCData(crate, slot.intValue(), channel.intValue());
                        short[] shortbuffer = new short[length];
                        for (int loop = 0; loop < length; loop++) {
                            Short sample = (Short) cdataitems.get(position + 2 + loop);
                            shortbuffer[loop] = sample;
                        }
                        bank.setBuffer(shortbuffer);
                        entries.add(bank);
                        position += 2 + length;
                        counter++;
                    }
                }
                return entries;

            } catch (EvioException ex) {
                ByteBuffer compBuffer = node.getByteData(true);
                System.out.println("Exception in CRATE = " + crate + "  RUN = " + this.runNumber
                        + "  EVENT = " + this.eventNumber + " LENGTH = " + compBuffer.array().length);
                this.printByteBuffer(compBuffer, 120, 20);
            }
        }
        return entries;
    }

    /*
    * 	<dictEntry name="FADC250 Window Raw Data (mode 1 packed)" tag="0xe126" num="0" type="composite">
    * <description format="c,m(c,ms)">
    *  c 	"slot number"
    * m	"number of channels fired"
    * c	"channel number"
    * m	"number of shorts in packed array"
    * s	"packed fadc data"
    * </description>
    * </dictEntry>
     */
    public void decodeComposite(ByteBuffer buffer, int offset, List<DataType> ctypes, List<Object> citems) {
        int position = offset;
        int length = buffer.capacity();
        try {
            while (position < (length - 3)) {
                Short slot = (short) (0x00FF & (buffer.get(position)));
                position++;
                citems.add(slot);
                ctypes.add(DataType.SHORT16);
                Short counter = (short) (0x00FF & (buffer.get(position)));
                citems.add(counter);
                ctypes.add(DataType.NVALUE);
                position++;

                for (int i = 0; i < counter; i++) {
                    Short channel = (short) (0x00FF & (buffer.get(position)));
                    position++;
                    citems.add(channel);
                    ctypes.add(DataType.SHORT16);
                    Short ndata = (short) (0x00FF & (buffer.get(position)));
                    position++;
                    citems.add(ndata);
                    ctypes.add(DataType.NVALUE);
                    for (int b = 0; b < ndata; b++) {
                        Short data = buffer.getShort(position);
                        position += 2;
                        citems.add(data);
                        ctypes.add(DataType.SHORT16);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Exception : Length = " + length + "  position = " + position);
        }
    }

    public List<FADCData> getDataEntries_57638(Integer crate, EvioNode node, EvioDataEvent event) {
        List<FADCData> entries = new ArrayList<>();
        if (node.getTag() == 57638) {
            ByteBuffer compBuffer = node.getByteData(true);
            List<DataType> cdatatypes = new ArrayList<>();
            List<Object> cdataitems = new ArrayList<>();
            this.decodeComposite(compBuffer, 24, cdatatypes, cdataitems);

            int position = 0;

            while (position < cdatatypes.size() - 3) {
                Short slot = (Short) cdataitems.get(position + 0);
                Short nchannels = (Short) cdataitems.get(position + 1);

                position += 2;
                int counter = 0;
                while (counter < nchannels) {
                    Short channel = (Short) cdataitems.get(position);
                    Short length = (Short) cdataitems.get(position + 1);
                    position += 2;
                    short[] shortbuffer = new short[length];
                    for (int loop = 0; loop < length; loop++) {
                        Short sample = (Short) cdataitems.get(position + loop);
                        shortbuffer[loop] = sample;
                    }
                    position += length;
                    counter++;
                    FADCData data = new FADCData(crate, slot, channel);
                    data.setBuffer(shortbuffer);
                    if (length > 18) {
                        entries.add(data);
                    }
                }
            }
        }
        return entries;
    }

    /**
     * decoding bank in Mode 1 - full ADC pulse.
     *
     * @param crate
     * @param node
     * @param event
     * @return
     */
    public List<DetectorDataDgtz> getDataEntries_57601(Integer crate, EvioNode node, EvioDataEvent event) {

        ArrayList<DetectorDataDgtz> entries = new ArrayList<>();

        if (node.getTag() == 57601) {
            try {

                ByteBuffer compBuffer = node.getByteData(true);
                CompositeData compData = new CompositeData(compBuffer.array(), event.getByteOrder());

                List<DataType> cdatatypes = compData.getTypes();
                List<Object> cdataitems = compData.getItems();

                if (cdatatypes.get(3) != DataType.NVALUE) {
                    System.err.println("[EvioRawDataSource] ** error ** corrupted "
                            + " bank. tag = " + node.getTag() + " num = " + node.getNum());
                    return null;
                }

                int position = 0;

                while (position < cdatatypes.size() - 4) {
                    Byte slot = (Byte) cdataitems.get(position + 0);
                    //Integer trig = (Integer)  cdataitems.get(position+1);
                    Long time = (Long) cdataitems.get(position + 2);

                    Integer nchannels = (Integer) cdataitems.get(position + 3);
                    position += 4;
                    int counter = 0;
                    while (counter < nchannels) {
                        Byte channel = (Byte) cdataitems.get(position);
                        Integer length = (Integer) cdataitems.get(position + 1);
                        DetectorDataDgtz bank = new DetectorDataDgtz(crate, slot.intValue(), channel.intValue());

                        short[] shortbuffer = new short[length];
                        for (int loop = 0; loop < length; loop++) {
                            Short sample = (Short) cdataitems.get(position + 2 + loop);
                            shortbuffer[loop] = sample;
                        }

                        bank.addPulse(shortbuffer);
                        bank.setTimeStamp(time);
                        entries.add(bank);
                        position += 2 + length;
                        counter++;
                    }
                }
                return entries;

            } catch (EvioException ex) {
                ByteBuffer compBuffer = node.getByteData(true);
                System.out.println("Exception in CRATE = " + crate + "  RUN = " + this.runNumber
                        + "  EVENT = " + this.eventNumber + " LENGTH = " + compBuffer.array().length);
                this.printByteBuffer(compBuffer, 120, 20);
//                Logger.getLogger(CodaEventDecoder.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        return entries;
    }

    public List<DetectorDataDgtz> getDataEntries_57627(Integer crate, EvioNode node, EvioDataEvent event) {

        ArrayList<DetectorDataDgtz> entries = new ArrayList<>();

        if (node.getTag() == 57627) {
            try {

                ByteBuffer compBuffer = node.getByteData(true);
                CompositeData compData = new CompositeData(compBuffer.array(), event.getByteOrder());

                List<DataType> cdatatypes = compData.getTypes();
                List<Object> cdataitems = compData.getItems();

                if (cdatatypes.get(3) != DataType.NVALUE) {
                    System.err.println("[EvioRawDataSource] ** error ** corrupted "
                            + " bank. tag = " + node.getTag() + " num = " + node.getNum());
                    return null;
                }

                int position = 0;

                while (position < cdatatypes.size() - 4) {
                    Byte slot = (Byte) cdataitems.get(position + 0);
                    //Integer trig = (Integer)  cdataitems.get(position+1);
                    Long time = (Long) cdataitems.get(position + 2);

                    Integer nchannels = (Integer) cdataitems.get(position + 3);
                    position += 4;
                    int counter = 0;
                    while (counter < nchannels) {

                        Short channel = (Short) cdataitems.get(position);
                        Integer length = (Integer) cdataitems.get(position + 1);
                        DetectorDataDgtz bank = new DetectorDataDgtz(crate, slot.intValue(), channel.intValue());

                        short[] shortbuffer = new short[length];
                        for (int loop = 0; loop < length; loop++) {
                            Short sample = (Short) cdataitems.get(position + 2 + loop);
                            shortbuffer[loop] = sample;
                        }
                        //Added pulse fitting for MMs
                        ADCData adcData = new ADCData();
                        adcData.setTimeStamp(time);
                        adcData.setPulse(shortbuffer);
                        bank.addADC(adcData);
                        entries.add(bank);
                        position += 2 + length;
                        counter++;
                    }
                }
                return entries;

            } catch (EvioException ex) {
                Logger.getLogger(CodaEventDecoder.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        return entries;
    }

    /**
     * Decoding MicroMegas Packed Data
     *
     * @param crate
     * @param node
     * @param event
     * @return
     */
    public List<DetectorDataDgtz> getDataEntries_57640(Integer crate, EvioNode node, EvioDataEvent event) {
        // Micromegas packed data
        // ----------------------

        ArrayList<DetectorDataDgtz> entries = new ArrayList<>();
        if (node.getTag() == 57640) {
            try {
                ByteBuffer compBuffer = node.getByteData(true);
                CompositeData compData = new CompositeData(compBuffer.array(), event.getByteOrder());

                List<DataType> cdatatypes = compData.getTypes();
                List<Object> cdataitems = compData.getItems();

                int jdata = 0;  // item counter
                for (int i = 0; i < cdatatypes.size();) { // loop over data types

                    Byte CRATE = (Byte) cdataitems.get(jdata++);
                    i++;
                    Integer EV_ID = (Integer) cdataitems.get(jdata++);
                    i++;
                    Long TIMESTAMP = (Long) cdataitems.get(jdata++);
                    i++;
                    Short nChannels = (Short) cdataitems.get(jdata++);
                    i++;

                    for (int ch = 0; ch < nChannels; ch++) {

                        Short CHANNEL = (Short) cdataitems.get(jdata++);
                        i++;
                        int nBytes = (Byte) cdataitems.get(jdata++);
                        i++;

                        DetectorDataDgtz bank = new DetectorDataDgtz(crate, CRATE.intValue(), CHANNEL.intValue());

                        int nSamples = nBytes * 8 / 12;
                        short[] samples = new short[nSamples];

                        int s = 0;
                        for (int b = 0; b < nBytes; b++) {
                            short data = (short) ((byte) cdataitems.get(jdata++) & 0xFF);

                            s = (int) Math.floor(b * 8. / 12.);
                            if (b % 3 != 1) {
                                samples[s] += (short) data;
                            } else {
                                samples[s] += (data & 0x000F) << 8;
                                if (s + 1 < nSamples) {
                                    samples[s + 1] += ((data & 0x00F0) >> 4) << 8;
                                }
                            }

                        }
                        i++;

                        ADCData adcData = new ADCData();
                        adcData.setTimeStamp(TIMESTAMP);
                        adcData.setPulse(samples);
                        bank.addADC(adcData);
                        entries.add(bank);
                    } // end loop on channels
                } // end loop on data types
                return entries;

            } catch (EvioException ex) {
                Logger.getLogger(CodaEventDecoder.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        return entries;
    }

    /**
     * Decoding MicroMegas Packed Data
     *
     * @param crate
     * @param node
     * @param event
     * @return
     */
    public List<DetectorDataDgtz> getDataEntries_57641(Integer crate, EvioNode node, EvioDataEvent event) {
        // Micromegas packed data
        // ----------------------

        ArrayList<DetectorDataDgtz> entries = new ArrayList<>();
        if (node.getTag() == 57641) {
            try {
                ByteBuffer compBuffer = node.getByteData(true);
                CompositeData compData = new CompositeData(compBuffer.array(), event.getByteOrder());

                List<DataType> cdatatypes = compData.getTypes();
                List<Object> cdataitems = compData.getItems();

                int jdata = 0;  // item counter
                for (int i = 0; i < cdatatypes.size();) { // loop over data types

                    Byte SLOT = (Byte) cdataitems.get(jdata++);
                    i++;
                    Integer EV_ID = (Integer) cdataitems.get(jdata++);
                    i++;
                    Long TIMESTAMP = (Long) cdataitems.get(jdata++);
                    i++;
                    Short nChannels = (Short) cdataitems.get(jdata++);
                    i++;

                    for (int ch = 0; ch < nChannels; ch++) {
                        Short CHANNEL = (Short) cdataitems.get(jdata++);
                        i++;

                        int nPulses = (Byte) cdataitems.get(jdata++);
                        i++;
                        for (int np = 0; np < nPulses; np++) {

                            int firstChannel = (Byte) cdataitems.get(jdata++);
                            i++;

                            int nBytes = (Byte) cdataitems.get(jdata++);
                            i++;

                            DetectorDataDgtz bank = new DetectorDataDgtz(crate, SLOT.intValue(), CHANNEL.intValue());

                            int nSamples = nBytes * 8 / 12;
                            short[] samples = new short[nSamples];

                            int s = 0;
                            for (int b = 0; b < nBytes; b++) {
                                short data = (short) ((byte) cdataitems.get(jdata++) & 0xFF);

                                s = (int) Math.floor(b * 8. / 12.);
                                if (b % 3 != 1) {
                                    samples[s] += (short) data;
                                } else {
                                    samples[s] += (data & 0x000F) << 8;
                                    if (s + 1 < nSamples) {
                                        samples[s + 1] += ((data & 0x00F0) >> 4) << 8;
                                    }
                                }
                            }
                            i++;

                            ADCData adcData = new ADCData();
                            adcData.setTimeStamp(TIMESTAMP);
                            adcData.setPulse(samples);
                            adcData.setTime(firstChannel);
                            bank.addADC(adcData);

                            entries.add(bank);
                        }
                    } // end loop on channels
                } // end loop on data types
                return entries;

            } catch (EvioException ex) {
                Logger.getLogger(CodaEventDecoder.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        return entries;
    }

    /**
     * Skeleton of URWell APV25 decoding
     *
     * @param crate
     * @param node
     * @param event
     * @return
     */
    public List<DetectorDataDgtz> getDataEntries_57631(Integer crate, EvioNode node, EvioDataEvent event) {

        final Integer APV_MIN_LENGTH = 64;
        final Short HEADER = 1500;
        final int n_APV_CH = 128;
        ArrayList<DetectorDataDgtz> entries = new ArrayList<>();

        if (node.getTag()
                == 57631) {

            /**
             * Note: this is made for decoding only SRS-APV data from uRWELL
             * test prototype. In this prototype only one FEC and only one Crate
             * is used. We will use this assumption, and make the code easier.
             * In case there will be more than one FEC and/or crate, then this
             * getDataEntries_57631 method will not work.
             *
             * The decoding algorithm was borrowed from the repository below,
             * and in particular from the given file
             * https://github.com/xbai0624/mpd_baseline_evaluation/blob/hb_quick_check/src/SRSRawEventDecoder.cpp
             */
            Map<Short, ArrayList<Short>> m_APV = new HashMap<>();

            Short HybridID = -1;
            int FECID = -1;

            int[] intBuff = node.getIntData();

            // =============== Switch Endianness
            for (int iBuf = 0; iBuf < intBuff.length; iBuf++) {
                intBuff[iBuf] = Integer.reverseBytes(intBuff[iBuf]);
            }

            //System.out.println("Data length = " + intBuff.length);
            for (int idata = 0; idata < intBuff.length; idata++) {

                /**
                 * When we meet 0x414443, then the least significant byte will
                 * represent the Hybrid ID, and the most significant 2 bytes of
                 * the next word represents the FEC ID. In our case we use only
                 * one 1 FEC, so it will always be 0. We also don't plan to
                 * use/store this variable in the output data file.
                 */
                //System.out.println("idata = " + idata);
                if (((intBuff[idata + 1] >> 8) & 0xffffff) == 0x414443) {

                    HybridID = (short) (intBuff[idata + 1] & 0xff);
                    FECID = (intBuff[idata + 2] >> 16) & 0xff;

                    if (m_APV.containsKey(HybridID) && m_APV.get(HybridID).size() > APV_MIN_LENGTH) {
                        System.err.println("Duplicate entry for the same Hybrid #" + HybridID);
                    }

                    //System.out.println(" HybridID = " + HybridID );
                    /**
                     * VERY Bad way of doing this, but this is just a temp
                     * solution to ignore GEM data from slots #12 and #13, as we
                     * don't have these data in the TT
                     *
                     */
                    m_APV.put(HybridID, new ArrayList<>());
//                    if (HybridID <= 11) {
//                    }
                    idata += 2;

                    /**
                     * The 0xfafafafa means the end of SRS data
                     */
                } else if (intBuff[idata + 1] == 0xfafafafa) {

                    /**
                     * One word (32 bit) here represents 2 APV data (each
                     * actually has least significant 12 bits only). Below we
                     * split the word into 2 Shorts, and then switch endiannes
                     * of it one more time
                     */
                    Short word16bit1 = (short) ((intBuff[idata] >> 16) & 0xffff);
                    word16bit1 = Short.reverseBytes(word16bit1);
                    Short word16bit2 = (short) (intBuff[idata] & 0xffff);
                    word16bit2 = Short.reverseBytes(word16bit2);

                    m_APV.get(HybridID).add(word16bit1);
                    m_APV.get(HybridID).add(word16bit2);
//                    if (HybridID <= 11) {
//                    }

                    idata += 1;
                } else {
                    /**
                     * In all other cases the data is just APV data, so will
                     * will fill the APV buffer, again after splitting it into
                     * two words and switching the endianness.
                     */
                    Short word16bit1 = (short) ((intBuff[idata] >> 16) & 0xffff);
                    word16bit1 = Short.reverseBytes(word16bit1);
                    Short word16bit2 = (short) (intBuff[idata] & 0xffff);
                    word16bit2 = Short.reverseBytes(word16bit2);

                    m_APV.get(HybridID).add(word16bit1);
                    m_APV.get(HybridID).add(word16bit2);
//                    if (HybridID <= 11) {
//                    }
                }
            }

            /**
             * All data is already read from the SRS crate. Now for each Hybrid,
             * will loop over APV data and from it will extract ADC data for
             * each channel and time sample. Then for each (Channel, time
             * sample) will create DetectorDataDgtz bank, and fill corresponding
             * ts and ADC.
             *
             * Here we don't use pedestal. The time sample will be assigned to
             * pedestal
             */
            /**
             * Loop over data for all Hybrids. The key of the map represents the
             * HybridID, and the value of the map is anr ArrayList representing
             * the APV data of the given Hybrid
             */
            for (Map.Entry<Short, ArrayList<Short>> entry : m_APV.entrySet()) {

                ArrayList<Short> cur_APV = entry.getValue();
                Short slot = entry.getKey(); // Here slot is the same as the HybridID

                DetectorDataDgtz[] bank = new DetectorDataDgtz[n_APV_CH];
                for (int ich = 0; ich < n_APV_CH; ich++) {
                    bank[ich] = new DetectorDataDgtz(crate, slot.intValue(), ich);
                }

                Short ts = 0; // ts = Time Sample
                for (int i_apv = 0; i_apv < cur_APV.size() - 3; i_apv++) {

                    // In APV the ADC starts when three consecutive entries have values less than the HEADER (value=1500).
                    // After those three samples we also should skip 9 additional data representing 8 (address) and 1 (error), then the next 128 channels should
                    // represent ADC values of the given timesample of the given APV. Then the pattern repeats itself 
                    if ((Short) (cur_APV.get(i_apv)) < HEADER && (Short) (cur_APV.get(i_apv + 1)) < HEADER && (Short) (cur_APV.get(i_apv + 2)) < HEADER && i_apv + 138 < cur_APV.size() + 1) {

                        i_apv = i_apv + 12; // Note 12 = 3 words < HEADED + 8 address word + 1 Error word // Details should be in APV documentation.

                        // Will determine the common mode here
                        // Will used the i_apvTmp to loop over cur_APV elements, and determine the common mode.
                        int i_apvTmp = i_apv;

                        double cmnMode = 0;
                        boolean specialSlot = (slot == 0 || slot == 6);

                        // * Will skip first and last 10 channels in the cmnMode determination
                        ArrayList<Integer> tmpList = new ArrayList<Integer>();
                        for (int ich = 0; ich < n_APV_CH; ich++) {

                            if (specialSlot) {
                                if (slot == 0) {
                                    if (32 * (ich % 4) + 8 * (ich / 4) - 31 * (ich / 16) >= 64) {
                                        //cmnMode = cmnMode + (double) cur_APV.get(i_apvTmp);
                                        tmpList.add((int) cur_APV.get(i_apvTmp));
                                    }
                                } else if (slot == 6) {
                                    if (32 * (ich % 4) + 8 * (ich / 4) - 31 * (ich / 16) < 64) {
                                        //cmnMode = cmnMode + (double) cur_APV.get(i_apvTmp);
                                        tmpList.add((int) cur_APV.get(i_apvTmp));
                                    }
                                }

                            } else {
                                //cmnMode = cmnMode + (double) cur_APV.get(i_apvTmp);
                                tmpList.add((int) cur_APV.get(i_apvTmp));
                            }
                            i_apvTmp = i_apvTmp + 1;
                        }

                        Collections.sort(tmpList);
                        for (int ich = 5; ich < tmpList.size(); ich++) {
                            cmnMode = cmnMode + tmpList.get(ich);
                        }
                        cmnMode = cmnMode / ((double) (tmpList.size() - 5));

                        //System.out.println(slot + "    " + cmnMode);
                        for (int ich = 0; ich < n_APV_CH; ich++) {
                            //DetectorDataDgtz bank = new DetectorDataDgtz(crate, slot.intValue(), ich);

                            ADCData adcData = new ADCData();
                            adcData.setIntegral((int) (cur_APV.get(i_apv) - cmnMode));
                            adcData.setPedestal(ts);
                            bank[ich].addADC(adcData);

                            i_apv = i_apv + 1;
                        }
                        i_apv = i_apv - 1;
                        ts++;

                    }
                }
                for (int ich = 0; ich < n_APV_CH; ich++) {
                    entries.add(bank[ich]);
                }

            }

//            if (time_OF_DetectorDataDgtz + time_OF_ADCData + time_OF_setIntegral + time_OF_setPedestal + time_OF_addADC + time_OF_add > 5) {
//                System.out.println(time_OF_DetectorDataDgtz + "    " + time_OF_ADCData + "    " + time_OF_setIntegral + "    " + time_OF_setPedestal + "    " + time_OF_addADC + "    " + time_OF_add);
//            }
        }
        return entries;
    }

    /* 
     * Decoding of VMM3 data, Format description at:
     * https://clonwiki0.jlab.org/wiki/clondocs/Docs/vmm3_L0_data_format.pdf
     */
    public List<DetectorDataDgtz> getDataEntries_57653(Integer crate, EvioNode node, EvioDataEvent event) {

        final Integer chip1Header = 3;
        final Integer chip1Trailer = 4;
        final Integer chip2Header = 5;
        final Integer chip2Trailer = 6;

        ArrayList<DetectorDataDgtz> entries = new ArrayList<>();

        if (node.getTag() == 57653) {

            int[] intBuff = node.getIntData();

            for (int iBuf = 0; iBuf < intBuff.length; iBuf++) {

                int type = intBuff[iBuf] >> 28;

                if (type == chip1Header || type == chip2Header) {
                    iBuf = getVMM3Hits(crate, entries, intBuff, iBuf);
                }

            }
        }

        return entries;

    }

    public Integer getVMM3Hits(Integer crate, List<DetectorDataDgtz> entries, int[] intBuff, int iBuf) {

        final Integer chip1Header = 3;
        final Integer chip1Trailer = 4;
        final Integer chip2Header = 5;
        final Integer chip2Trailer = 6;

        Integer slot = 30; // The raw data doesn't have any information about the slot. We will just hardcode a value, and also put in the TT

        int type = intBuff[iBuf] >> 28;
        int channelOffset = 0;

        if (type == chip1Header) {
            channelOffset = 0;
        } else if (type == chip2Header) {
            channelOffset = 64;
        } else {
            System.out.println("This is not a VMM Chip header. Exiting...");
            System.exit(1);
        }

        int nHitMask = 16711680; // bits 23 to 16 are 1, the rest is 0 "111111110000000000000000"
        int nChipHits = (intBuff[iBuf] & nHitMask) >> 16;

        for (int iHit = 0; iHit < nChipHits; iHit++) {

            Integer mask_relBCID = 7;           //bits 31-29
            Integer mask_N = 1;                 // bit 28
            Integer mask_TDC = 255;             // bits 27-20
            Integer mask_ADC = 1023;            // bits 19-10
            Integer mask_channel = 63;          // bits 9-4
            Integer mask_T = 1;                 // bit 3
            Integer mask_R = 1;                 // bit 2
            Integer mask_P = 1;                 // bit 1          

//            Integer channel = (intBuff[iBuf + iHit + 1] >> 4) & mask_channel + channelOffset;
//            Integer adc = (intBuff[iBuf + iHit + 1] >> 10) & mask_ADC;
//            Integer tdc = (intBuff[iBuf + iHit + 1] >> 20) & mask_TDC;
//            Short relBCID = (short) ((intBuff[iBuf + iHit + 1] >> 29) & mask_relBCID);
//            Boolean N = (((intBuff[iBuf + iHit + 1] >> 28) & mask_N) != 0);
//            Boolean P = (((intBuff[iBuf + iHit + 1] >> 1) & mask_P) != 0);
//            Boolean R = (((intBuff[iBuf + iHit + 1] >> 2) & mask_R) != 0);
//            Boolean T = (((intBuff[iBuf + iHit + 1] >> 3) & mask_T) != 0);



            Integer channel = ((intBuff[iBuf + iHit + 1] >> 22) & mask_channel) + channelOffset;  // d
            Integer adc = (intBuff[iBuf + iHit + 1] >> 12) & mask_ADC;           // d   
            Integer tdc = (intBuff[iBuf + iHit + 1] >> 4) & mask_TDC;            // d
            Short relBCID = (short) ((intBuff[iBuf + iHit + 1]) & mask_relBCID); // d
            Boolean N = (((intBuff[iBuf + iHit + 1] >> 3) & mask_N) != 0);       // d
            Boolean P = (((intBuff[iBuf + iHit + 1] >> 30) & mask_P) != 0);      // d
            Boolean R = (((intBuff[iBuf + iHit + 1] >> 29) & mask_R) != 0);      // d
            Boolean T = (((intBuff[iBuf + iHit + 1] >> 28) & mask_T) != 0);      // d

            
            
            
            //System.out.println("The word is " + Integer.toBinaryString( intBuff[iBuf + iHit + 1] )  + "    Channel is " + channel + "   Type = " + type + "  Offset = " + channelOffset );
            
            /*
                        * Forming the PRTN,
             */
            Short PRTN = 0;
            PRTN = (short) (N ? PRTN | 1 : PRTN);
            PRTN = (short) (T ? PRTN | 1 << 1 : PRTN);
            PRTN = (short) (R ? PRTN | 1 << 2 : PRTN);
            PRTN = (short) (P ? PRTN | 1 << 3 : PRTN);

            ADCData adcData = new ADCData();
            adcData.setIntegral(adc);
            adcData.setTime(tdc);
            adcData.setHeight(relBCID);
            adcData.setPedestal(PRTN);

            DetectorDataDgtz bank = new DetectorDataDgtz(crate, slot, channel);

            bank.addADC(adcData);
            entries.add(bank);
        }

        return iBuf + nChipHits + 1;
    }

    /**
     * Decoding MODE 7 data. for given crate.
     *
     * @param crate
     * @param node
     * @param event
     * @return
     */
    public List<DetectorDataDgtz> getDataEntries_57602(Integer crate, EvioNode node, EvioDataEvent event) {
        List<DetectorDataDgtz> entries = new ArrayList<>();
        if (node.getTag() == 57602) {
            try {
                ByteBuffer compBuffer = node.getByteData(true);
                CompositeData compData = new CompositeData(compBuffer.array(), event.getByteOrder());

                List<DataType> cdatatypes = compData.getTypes();
                List<Object> cdataitems = compData.getItems();

                if (cdatatypes.get(3) != DataType.NVALUE) {
                    System.err.println("[EvioRawDataSource] ** error ** corrupted "
                            + " bank. tag = " + node.getTag() + " num = " + node.getNum());
                    return null;
                }

                int position = 0;
                while ((position + 4) < cdatatypes.size()) {

                    Byte slot = (Byte) cdataitems.get(position + 0);
                    //Integer trig = (Integer)  cdataitems.get(position+1);
                    Long time = (Long) cdataitems.get(position + 2);

                    Integer nchannels = (Integer) cdataitems.get(position + 3);
                    position += 4;
                    int counter = 0;
                    while (counter < nchannels) {
                        Byte channel = (Byte) cdataitems.get(position);
                        Integer length = (Integer) cdataitems.get(position + 1);

                        position += 2;
                        for (int loop = 0; loop < length; loop++) {
                            Short tdc = (Short) cdataitems.get(position);
                            Integer adc = (Integer) cdataitems.get(position + 1);
                            Short pmin = (Short) cdataitems.get(position + 2);
                            Short pmax = (Short) cdataitems.get(position + 3);
                            DetectorDataDgtz entry = new DetectorDataDgtz(crate, slot, channel);
                            ADCData adcData = new ADCData();
                            adcData.setIntegral(adc).setTimeWord(tdc).setPedestal(pmin).setHeight(pmax);
                            entry.addADC(adcData);
                            entry.setTimeStamp(time);
                            entries.add(entry);
                            position += 4;
                        }
                        counter++;
                    }
                }
                return entries;

            } catch (EvioException ex) {
                Logger.getLogger(CodaEventDecoder.class
                        .getName()).log(Level.SEVERE, null, ex);
            }
        }
        return entries;
    }

    /**
     * Bank TAG=57622 used for DC (Drift Chambers) TDC values.
     *
     * @param crate
     * @param node
     * @param event
     * @return
     */
    public List<DetectorDataDgtz> getDataEntries_57622(Integer crate, EvioNode node, EvioDataEvent event) {
        List<DetectorDataDgtz> entries = new ArrayList<>();
        if (node.getTag() == 57622) {
            try {
                ByteBuffer compBuffer = node.getByteData(true);
                CompositeData compData = new CompositeData(compBuffer.array(), event.getByteOrder());
                //List<DataType> cdatatypes = compData.getTypes();
                List<Object> cdataitems = compData.getItems();

                int totalSize = cdataitems.size();
                int position = 0;
                while ((position + 4) < totalSize) {
                    Byte slot = (Byte) cdataitems.get(position);
                    //Integer trig = (Integer)  cdataitems.get(position+1);
                    Long time = (Long) cdataitems.get(position + 2);
                    Integer nchannels = (Integer) cdataitems.get(position + 3);
                    int counter = 0;
                    position = position + 4;
                    while (counter < nchannels) {
                        Byte channel = (Byte) cdataitems.get(position);
                        Short tdc = (Short) cdataitems.get(position + 1);
                        position += 2;
                        counter++;
                        DetectorDataDgtz entry = new DetectorDataDgtz(crate, slot, channel);
                        entry.addTDC(new TDCData(tdc));
                        entry.setTimeStamp(time);
                        entries.add(entry);
                    }
                }
            } catch (EvioException ex) {
                //Logger.getLogger(EvioRawDataSource.class.getName()).log(Level.SEVERE, null, ex);
            } catch (IndexOutOfBoundsException ex) {
                //System.out.println("[ERROR] ----> ERROR DECODING COMPOSITE DATA FOR ONE EVENT");
            }

        }
        return entries;
    }

    /**
     * Bank TAG=57636 used for RICH TDC values
     *
     * @param crate
     * @param node
     * @param event
     * @return
     */
    public List<DetectorDataDgtz> getDataEntries_57636(Integer crate, EvioNode node, EvioDataEvent event) {

        ArrayList<DetectorDataDgtz> entries = new ArrayList<>();

        if (node.getTag() == 57636) {
            try {

                ByteBuffer compBuffer = node.getByteData(true);
                CompositeData compData = new CompositeData(compBuffer.array(), event.getByteOrder());

                List<DataType> cdatatypes = compData.getTypes();
                List<Object> cdataitems = compData.getItems();

                if (cdatatypes.get(3) != DataType.NVALUE) {
                    System.err.println("[EvioRawDataSource] ** error ** corrupted "
                            + " bank. tag = " + node.getTag() + " num = " + node.getNum());
                    return null;
                }

                int position = 0;
                while (position < cdatatypes.size() - 4) {
                    Byte slot = (Byte) cdataitems.get(position + 0);
                    //Integer trig = (Integer)  cdataitems.get(position+1);
                    //Long    time = (Long)     cdataitems.get(position+2);

                    Integer nchannels = (Integer) cdataitems.get(position + 3);
                    position += 4;
                    int counter = 0;

                    while (counter < nchannels) {
                        Integer fiber = ((Byte) cdataitems.get(position)) & 0xFF;
                        Integer channel = ((Byte) cdataitems.get(position + 1)) & 0xFF;
                        Short rawtdc = (Short) cdataitems.get(position + 2);
                        int edge = (rawtdc >> 15) & 0x1;
                        int tdc = rawtdc & 0x7FFF;

                        DetectorDataDgtz bank = new DetectorDataDgtz(crate, slot.intValue(), 2 * (fiber * 192 + channel) + edge);
                        bank.addTDC(new TDCData(tdc));

                        entries.add(bank);
                        position += 3;
                        counter++;
                    }
                }

                return entries;

            } catch (EvioException ex) {
                Logger.getLogger(CodaEventDecoder.class
                        .getName()).log(Level.SEVERE, null, ex);
            }
        }
        return entries;
    }

    /**
     * Bank TAG=57655 used for XY Hodoscope TDC values
     *
     * @param crate
     * @param node
     * @param event
     * @return
     */
    public List<DetectorDataDgtz> getDataEntries_57655(Integer crate, EvioNode node, EvioDataEvent event) {

        ArrayList<DetectorDataDgtz> entries = new ArrayList<>();

        if (node.getTag() == 57655) {
            try {
                ByteBuffer compBuffer = node.getByteData(true);
                CompositeData compData = new CompositeData(compBuffer.array(), event.getByteOrder());

                List<DataType> cdatatypes = compData.getTypes();
                List<Object> cdataitems = compData.getItems();

                if (cdatatypes.get(3) != DataType.NVALUE) {
                    System.err.println("[EvioRawDataSource] ** error ** corrupted "
                            + " bank. tag = " + node.getTag() + " num = " + node.getNum());
                    return null;
                }

                int position = 0;
                while (position < cdatatypes.size() - 4) {
                    Integer slot = (Byte) (cdataitems.get(position + 0)) + this.HodoSlotOffset;

                    //Integer trig = (Integer)  cdataitems.get(position+1);
                    //Long    time = (Long)     cdataitems.get(position+2);
                    Integer nchannels = (Integer) cdataitems.get(position + 3);
                    position += 4;
                    int counter = 0;

                    int PMT = 1 - slot % 2; // PMT 0 ist the the one closest to low number bars, PMT 1 is the one close to high number bars

                    //System.out.println("Crate = " + crate + "      Slot = " + slot + "      nchannels = " + nchannels);
                    while (counter < nchannels) {
                        //Integer fiber = ((Byte) cdataitems.get(position))&0xFF;  // The Fiber information is present in RICH data, but for Hodoscope it is not needed
                        Integer channel = ((Byte) cdataitems.get(position)) & 0xFF;
                        Short rawtdc = (Short) cdataitems.get(position + 1);
                        int edge = (rawtdc >> 15) & 0x1;
                        int tdc = rawtdc & 0x7FFF;

                        //System.out.println( "Channel = " + channel + "     rawtdc = " + rawtdc + "      edge is " + edge + "      tdc = " + tdc );
                        int MAROC_ID = channel / 64;
                        int MAROC_Channel = channel % 64 + 1;

                        int TT_Channel = 2 * (channel - MAROC_ID * 64) + edge; //

                        DetectorDataDgtz bank = new DetectorDataDgtz(crate, slot.intValue(), TT_Channel);
                        bank.addTDC(new TDCData(tdc));

                        entries.add(bank);
                        position += 2;
                        counter++;
                    }
                }

                return entries;
            } catch (EvioException ex) {
                Logger.getLogger(CodaEventDecoder.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        return entries;
    }

    public void getDataEntries_EPICS(EvioDataEvent event) {
        epicsData = new JsonObject();
        List<EvioTreeBranch> branches = this.getEventBranches(event);
        for (EvioTreeBranch branch : branches) {
            for (EvioNode node : branch.getNodes()) {
                if (node.getTag() == 57620) {
                    byte[] stringData = ByteDataTransformer.toByteArray(node.getStructureBuffer(true));
                    String cdata = new String(stringData);
                    String[] vars = cdata.trim().split("\n");
                    for (String var : vars) {
                        String[] fields = var.trim().replaceAll("  ", " ").split(" ");
                        if (fields.length != 2) {
                            continue;
                        }
                        String key = fields[1].trim();
                        String sval = fields[0].trim();
                        try {
                            float fval = Float.parseFloat(sval);
                            epicsData.add(key, fval);
                        } catch (NumberFormatException e) {
                            System.err.println("WARNING:  Ignoring EPICS Bank row:  " + var);
                        }
                    }
                }
            }
        }
    }

    public HelicityDecoderData getDataEntries_HelicityDecoder(EvioDataEvent event) {
        HelicityDecoderData data = null;
        List<EvioTreeBranch> branches = this.getEventBranches(event);
        for (EvioTreeBranch branch : branches) {
            for (EvioNode node : branch.getNodes()) {
                if (node.getTag() == 57651) {

                    long[] longData = ByteDataTransformer.toLongArray(node.getStructureBuffer(true));
                    int[] intData = ByteDataTransformer.toIntArray(node.getStructureBuffer(true));
                    long timeStamp = longData[2] & 0x0000ffffffffffffL;

                    int tsettle = DataUtils.getInteger(intData[16], 0, 0) > 0 ? 1 : -1;
                    int pattern = DataUtils.getInteger(intData[16], 1, 1) > 0 ? 1 : -1;
                    int pair = DataUtils.getInteger(intData[16], 2, 2) > 0 ? 1 : -1;
                    int helicity = DataUtils.getInteger(intData[16], 3, 3) > 0 ? 1 : -1;
                    int start = DataUtils.getInteger(intData[16], 4, 4) > 0 ? 1 : -1;
                    int polarity = DataUtils.getInteger(intData[16], 5, 5) > 0 ? 1 : -1;
                    int count = DataUtils.getInteger(intData[16], 8, 11);
                    data = new HelicityDecoderData((byte) helicity, (byte) pair, (byte) pattern);
                    data.setTimestamp(timeStamp);
                    data.setHelicitySeed(intData[7]);
                    data.setNTStableRisingEdge(intData[8]);
                    data.setNTStableFallingEdge(intData[9]);
                    data.setNPattern(intData[10]);
                    data.setNPair(intData[11]);
                    data.setTStableStart(intData[12]);
                    data.setTStableEnd(intData[13]);
                    data.setTStableTime(intData[14]);
                    data.setTSettleTime(intData[15]);
                    data.setTSettle((byte) tsettle);
                    data.setHelicityPattern((byte) start);
                    data.setPolarity((byte) polarity);
                    data.setPatternPhaseCount((byte) count);
                    data.setPatternWindows(intData[17]);
                    data.setPairWindows(intData[18]);
                    data.setHelicityWindows(intData[19]);
                    data.setHelicityPatternWindows(intData[20]);
                }
            }
        }
        return data;
    }

    public List<DetectorDataDgtz> getDataEntries_Scalers(EvioDataEvent event) {

        List<DetectorDataDgtz> scalerEntries = new ArrayList<>();
        List<EvioTreeBranch> branches = this.getEventBranches(event);
        for (EvioTreeBranch branch : branches) {
            int crate = branch.getTag();
            for (EvioNode node : branch.getNodes()) {
                if (node.getTag() == 57637 || node.getTag() == 57621) {
                    int num = node.getNum();
                    int[] intData = ByteDataTransformer.toIntArray(node.getStructureBuffer(true));
                    for (int loop = 2; loop < intData.length; loop++) {
                        int dataEntry = intData[loop];
                        if (node.getTag() == 57637) {
                            int helicity = DataUtils.getInteger(dataEntry, 31, 31);
                            int quartet = DataUtils.getInteger(dataEntry, 30, 30);
                            int interval = DataUtils.getInteger(dataEntry, 29, 29);
                            int id = DataUtils.getInteger(dataEntry, 24, 28);
                            long value = DataUtils.getLongFromInt(DataUtils.getInteger(dataEntry, 0, 23));
                            if (id < 3) {
                                DetectorDataDgtz entry = new DetectorDataDgtz(crate, num, id + 32 * interval);
                                SCALERData scaler = new SCALERData();
                                scaler.setHelicity((byte) helicity);
                                scaler.setQuartet((byte) quartet);
                                scaler.setValue(value);
                                entry.addSCALER(scaler);
                                scalerEntries.add(entry);
                            }
                        } else if (node.getTag() == 57621 && loop >= 5) {
                            int id = (loop - 5) % 16;
                            int slot = (loop - 5) / 16;
                            if (id < 3 && slot < 4) {
                                DetectorDataDgtz entry = new DetectorDataDgtz(crate, num, loop - 5);
                                SCALERData scaler = new SCALERData();
                                scaler.setValue(DataUtils.getLongFromInt(dataEntry));
                                entry.addSCALER(scaler);
                                scalerEntries.add(entry);
                            }
                        }
                    }
                }
            }
        }
        return scalerEntries;
    }

    public List<DetectorDataDgtz> getDataEntries_VTP(EvioDataEvent event) {

        List<DetectorDataDgtz> vtpEntries = new ArrayList<>();
        List<EvioTreeBranch> branches = this.getEventBranches(event);
        for (EvioTreeBranch branch : branches) {
            int crate = branch.getTag();
            for (EvioNode node : branch.getNodes()) {
                if (node.getTag() == 57634) {
                    int[] intData = ByteDataTransformer.toIntArray(node.getStructureBuffer(true));
                    for (int loop = 0; loop < intData.length; loop++) {
                        int dataEntry = intData[loop];
                        DetectorDataDgtz entry = new DetectorDataDgtz(crate, 0, 0);
                        entry.addVTP(new VTPData(dataEntry));
                        vtpEntries.add(entry);
                    }
                }
            }
        }
        return vtpEntries;
    }

    /**
     * reads the TDC values from the bank with tag = 57607, decodes them and
     * returns a list of digitized detector object.
     *
     * @param event
     * @return
     */
    public List<DetectorDataDgtz> getDataEntries_TDC(EvioDataEvent event) {

        List<DetectorDataDgtz> tdcEntries = new ArrayList<>();
        List<EvioTreeBranch> branches = this.getEventBranches(event);

        for (EvioTreeBranch branch : branches) {
            int crate = branch.getTag();
            EvioTreeBranch cbranch = this.getEventBranch(branches, branch.getTag());
            for (EvioNode node : cbranch.getNodes()) {
                if (node.getTag() == 57607) {
                    int[] intData = ByteDataTransformer.toIntArray(node.getStructureBuffer(true));
                    for (int loop = 2; loop < intData.length; loop++) {
                        int dataEntry = intData[loop];
                        int slot = DataUtils.getInteger(dataEntry, 27, 31);
                        int chan = DataUtils.getInteger(dataEntry, 19, 25);
                        int value = DataUtils.getInteger(dataEntry, 0, 18);
                        DetectorDataDgtz entry = new DetectorDataDgtz(crate, slot, chan);
                        entry.addTDC(new TDCData(value));
                        tdcEntries.add(entry);
                    }
                }
            }
        }
        return tdcEntries;
    }

    /**
     * decoding bank that contains TI time stamp.
     *
     * @param event
     * @return
     */
    public List<DetectorDataDgtz> getDataEntries_TI(EvioDataEvent event) {

        List<DetectorDataDgtz> tiEntries = new ArrayList<>();
        List<EvioTreeBranch> branches = this.getEventBranches(event);
        for (EvioTreeBranch branch : branches) {
            int crate = branch.getTag();
            EvioTreeBranch cbranch = this.getEventBranch(branches, branch.getTag());
            for (EvioNode node : cbranch.getNodes()) {
                if (node.getTag() == 57610) {
                    long[] longData = ByteDataTransformer.toLongArray(node.getStructureBuffer(true));
                    int[] intData = ByteDataTransformer.toIntArray(node.getStructureBuffer(true));

                    // Below is endian swap if needed
                    //long    ntStamp = (((long)(intData[5]&0x0000ffffL))<<32) | (intData[4]&0xffffffffL);
                    //System.out.println(longData[2]+" "+tStamp+" "+crate+" "+node.getDataLength());
                    DetectorDataDgtz entry = new DetectorDataDgtz(crate, 0, 0);
                    if (longData.length > 2) {
                        long tStamp = longData[2] & 0x0000ffffffffffffL;
                        entry.setTimeStamp(tStamp);
                    }
                    if (node.getDataLength() == 4) {
                        tiEntries.add(entry);
                    } else if (node.getDataLength() == 5) { // trigger supervisor crate
                        this.setTriggerBits(intData[6]);
                    } else if (node.getDataLength() == 6) { // New format Dec 1 2017 (run 1701)
                        this.setTriggerBits(intData[6] << 16 | intData[7]);
                    } else if (node.getDataLength() == 7) { // New format Dec 1 2017 (run 1701)
                        long word = (((long) intData[7]) << 32) | (intData[6] & 0xffffffffL);
                        this.setTriggerBits(word);
                        this.triggerWords.clear();
                        for (int i = 6; i <= 8; i++) {
                            this.triggerWords.add(intData[i]);
                        }
                    }
                }
            }
        }

        return tiEntries;
    }

    public static void main(String[] args) {

        System.out.println("******** CCDB_CONNECTION = " + System.getenv("CCDB_CONNECTION"));

        EvioSource reader = new EvioSource();
        //reader.open("/Users/devita/clas_004013.evio.1000");
        //reader.open("/work/clas12/rafopar/uRWELL/Readout/APV25/urwell_001534.evio.00000");
        //reader.open("/work/clas12/rafopar/uRWELL/Readout/APV25/urwell_001576.evio.00000");
        //reader.open("/cache/clas12/detectors/uRwell/2024_EEL_Hodo_And_uRwell/urwell_maroc_002151.evio.00000");
        reader.open("/work/clas12/rafopar/uRWELL/Readout/VMM3/vmm_000112.evio.00000");
        //reader.open("/work/clas12/rafopar/uRWELL/Readout/APV25/urwell_001326.evio.00000");
        CodaEventDecoder decoder = new CodaEventDecoder();
        DetectorEventDecoder detectorDecoder = new DetectorEventDecoder();

        int maxEvents = 100;
        int icounter = 0;

        while (reader.hasEvent() == true && icounter < maxEvents) {

            EvioDataEvent event = (EvioDataEvent) reader.getNextEvent();
            //System.out.println("Event number is " + icounter);
            List<DetectorDataDgtz> dataSet = decoder.getDataEntries(event);
            detectorDecoder.translate(dataSet);
            detectorDecoder.fitPulses(dataSet);
            icounter++;
        }
        System.out.println("Done...");
    }
}
