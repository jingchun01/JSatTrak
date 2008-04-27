package gov.nasa.worldwind.formats.nitfs;

import gov.nasa.worldwind.formats.rpf.*;
import gov.nasa.worldwind.geom.LatLon;
import gov.nasa.worldwind.util.StringUtil;

import java.nio.*;
/*
Copyright (C) 2001, 2007 United States Government
as represented by the Administrator of the
National Aeronautics and Space Administration.
All Rights Reserved.
*/

/**
 * @author Lado Garakanidze
 * @version $Id: NitfsImageSegment Mar 30, 2007 12:21:34 PM lado
 */
public class NITFSImageSegment extends NITFSSegment
{
    public static final String[] SupportedFormats = { "CIB", "CADRG", "ADRG" };
    // [ nitf identification , security, structure fields]
    public String partType;
    public String imageID;
    public String dateTime;
    public String targetID;
    public String imageTitle;
    public String securityClass;
    public String codewords;
    public String controlAndHandling;
    public String releaseInstructions;
    public String classAuthority;
    public String securityCtrlNum;
    public String ISDWNG;                       // image security downgrade
    public String ISDEVT;                       // downgrading event
    public short  encryption;
    public String imageSource;
    public int    numSignificantRows;
    public int    numSignificantCols;
    public String pixelValueType;
    public String imageRepresentation;
    public String imageCategory;
    public short  bitsPerPixelPerBand;
    public String pixelJustification;
    public String imageCoordSystem;
    // [ nitf image geographic location ]
    public LatLon[] imageCoords;
    // [ nitf comments ]
    public String[] imageCommentRecords;
    // [ nitf image compression structure ]
    public String imageCompression;
    public String compressionRateCode;
    public short    NBANDS;                     // number of bands { 1 for MONO and RGB/LUT, 3 for RGB;
    // [ nitfs image bands ]
    public NITFSImageBand[] imageBands;
    // [ nitf image table structure fields ]
    public short    imageSyncCode;              // ISYNC { 0 - No sync code, 1 - sync code }
    public String   imageMode;                  // IMODE { B, P, R, S }
    public short    numOfBlocksPerRow;          // NBPR   { 0001~9999 }
    public short    numOfBlocksPerCol;          // NBPC   { 0001~9999 }
    public short    numOfPixelsPerBlockH;       // NPPBH  { 0001~8192 }
    public short    numOfPixelsPerBlockV;       // NPPBV  { 0001~8192 }
    public short    numOfBitsPerPixelPerBand;   // NBPP   { 01~96 }
    public short    displayLevel;               // IDLVL  { 001~999 }
    public short    attachmentLevel;            // IALVL  { 001~998 }
    // [ nitfs image location ]
    public short    imageRowOffset;             // ILOC   { -0001 ~ +9999 }
    public short    imageColOffset;             //

    // [ nitf image magnification ]
    public String   imageMagnification;         // IMAG
    public short    userDefinedSubheaderLength;

    // [ nitf user-defined image subheader ]
    private UserDefinedImageSubheader userDefSubheader;

    // [ nitf-rpf image display parameter sub-header ]
    private long    numOfImageRows;
    private long    numOfImageCodesPerRow;
    private short   imageCodeBitLength;

    // [ nitf rpf compression section ]
    //      [ nitf-rpf compression section sub-header ]
    private int     compressionAlgorithmID;
    private int     numOfCompressionLookupOffsetRecords;
    private int     numOfCompressionParameterOffsetRecords;

    //      [ nitf rpf compression lookup sub-section ]
    private long    compressionLookupOffsetTableOffset;
    private int     compressionLookupTableOffsetRecordLength;


    // [ nitf-rpf mask subsection ]
    private int     subframeSequenceRecordLength;
    private int     transparencySequenceRecordLength;
    private int     transparentOutputPixelCodeLength;
    private byte[]  transparentOutputPixelCode;
    private int[]   subFrameOffsets = null;

    private boolean hasTransparentPixels = false;
    private boolean hasMaskedSubframes = false;

    public static String[] getSupportedFormats()
    {
        return SupportedFormats;
    }

    public boolean hasTransparentPixels()
    {
        return this.hasTransparentPixels;
    }

    public boolean hasMaskedSubframes()
    {
        return this.hasMaskedSubframes;
    }

    private CompressionLookupRecord[] compressionLUTS;

    public UserDefinedImageSubheader getUserDefinedImageSubheader()
    {
        return userDefSubheader;
    }

    public RPFFrameFileComponents getRPFFrameFileComponents()
    {
        return (null != userDefSubheader) ? userDefSubheader.getRPFFrameFileComponents() : null;
    }

    public NITFSImageSegment(java.nio.ByteBuffer buffer, int headerStartOffset, int headerLength,int dataStartOffset, int dataLength)
    {
        super(NITFSSegmentType.IMAGE_SEGMENT, buffer, headerStartOffset, headerLength, dataStartOffset, dataLength);

        int saveOffset = buffer.position();

        buffer.position( headerStartOffset );
        // do not change order of parsing
        this.parseIdentificationSecurityStructureFields(buffer);
        this.parseImageGeographicLocation(buffer);
        this.parseCommentRecords(buffer);
        this.parseImageCompressionStructure(buffer);
        this.parseImageBands(buffer);
        this.parseImageTableStructure(buffer);
        this.parseImageLocation(buffer);
        this.parseImageSubheaders(buffer);
        this.parseImageData(buffer);
        this.validateImage();

        buffer.position(saveOffset); // last line - restore buffer's position
    }

    private void decompressBlock4x4(byte[][] block4x4, short code)
    {
        this.compressionLUTS[0].copyValues(block4x4[0], 0, code, 4);
        this.compressionLUTS[1].copyValues(block4x4[1], 0, code, 4);
        this.compressionLUTS[2].copyValues(block4x4[2], 0, code, 4);
        this.compressionLUTS[3].copyValues(block4x4[3], 0, code, 4);
    }

    private void decompressBlock16(byte[] block16, short code)
    {
        this.compressionLUTS[0].copyValues(block16,  0, code, 4);
        this.compressionLUTS[1].copyValues(block16,  4, code, 4);
        this.compressionLUTS[2].copyValues(block16,  8, code, 4);
        this.compressionLUTS[3].copyValues(block16, 12, code, 4);
    }


    public ByteBuffer getImageAsDdsTexture() throws NITFSRuntimeException {
        RPFFrameFileComponents rpfComponents = this.getRPFFrameFileComponents();
        RPFLocationSection componentLocationTable = rpfComponents.componentLocationTable;

        int spatialDataSubsectionLocation = componentLocationTable.getSpatialDataSubsectionLocation();
        super.buffer.position( spatialDataSubsectionLocation );

        short aa, ab, bb;
        short[] codes = new short[(int) this.numOfImageCodesPerRow];
        byte[]  block16 = new byte[16];
        int rowSize = (int) ((this.numOfImageCodesPerRow * this.imageCodeBitLength) / 8L);
        byte[] rowBytes = new byte[rowSize];
        int subFrameOffset;
        short subFrameIdx = 0;

        int band = 0; // for(int band = 0; band < rpfComponents.numOfSpectralBandTables;  band++)
        NITFSImageBand imageBand = this.imageBands[band];

        RPF2DDSCompress ddsCompress = (1 == imageBand.getNumOfLookupTables())
            ? new CIB2DDSCompress() : new CADRG2DDSCompress();

        boolean imageHasTransparentAreas = ( this.hasTransparentPixels || this.hasMaskedSubframes );
        if( imageHasTransparentAreas )
        {

        }

        // Allocate space for the DDS texture
        int bufferSize = 128 + this.numSignificantCols * this.numSignificantRows / 2;
        java.nio.ByteBuffer ddsBuffer = java.nio.ByteBuffer.allocateDirect(bufferSize);
        ddsBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        ddsCompress.writeDxt1Header(ddsBuffer, this.numSignificantCols, this.numSignificantRows);
        int ddsHeaderLength = ddsBuffer.position();

        
        for (int subFrameH = 0; subFrameH < this.numOfBlocksPerCol; subFrameH++)
        {
            for (int subFrameW = 0; subFrameW < this.numOfBlocksPerRow; subFrameW++, subFrameIdx++ )
            {
                int blockY = (int) (subFrameH * rpfComponents.numOfOutputRowsPerSubframe);
                int blockX = (int) (subFrameW * rpfComponents.numOfOutputColumnsPerSubframe);

                if(hasMaskedSubframes)
                {
                    subFrameOffset = this.subFrameOffsets[subFrameIdx];
                    if( -1 == subFrameOffset)
                    {   // this is a masked / transparent(?) subframe
                        DDSBlock4x4 ddsBlock = ddsCompress.getDxt1TransparentBlock4x4();

                        for (int row = 0; row < this.numOfImageRows; row++)
                        {
                            int qy = blockY + row * 4;
                            for (int col = 0; col < this.numOfImageCodesPerRow; col++)
                            {
                                int qx = blockX + col * 4;
                                ddsBuffer.position(ddsHeaderLength + (qy * this.numSignificantCols) / 2 + 2 * qx);
                                ddsBlock.writeTo( ddsBuffer );
                            } // end of column loop
                        }
                        continue;
                    }
                    else
                    {
                        super.buffer.position( spatialDataSubsectionLocation + subFrameOffset );
                    }
                }

                for (int row = 0; row < this.numOfImageRows; row++)
                {
                    int qy = blockY + row * 4;
                    super.buffer.get(rowBytes, 0, rowSize);

                    for (int i = 0, cidx = 0, bidx = 0; i < (int) this.numOfImageCodesPerRow / 2; i++)
                    {
                        aa = (short) ((0x00FF & (short) rowBytes[bidx++]) << 4);
                        ab = (short) (0x00FF & (short) rowBytes[bidx++]);
                        bb = (short) (0x00FF & (short) rowBytes[bidx++]);

                        codes[cidx++] = (short) (aa | ((0x00F0 & ab) >> 4));
                        codes[cidx++] = (short) (bb | ((0x000F & ab) << 8));
                    }

                    for (int col = 0; col < this.numOfImageCodesPerRow; col++)
                    {
                        int qx = blockX + col * 4;
                        this.decompressBlock16( block16, codes[col] );

                        DDSBlock4x4 ddsBlock = ddsCompress.compressDxt1Block4x4( imageBand, block16, false );

                        ddsBuffer.position( ddsHeaderLength + (qy * this.numSignificantCols)/2 + 2 * qx );
                        ddsBlock.writeTo( ddsBuffer );
                    } // end of column loop
                } // end of row loop
            } // end of subFrameW loop
        } // end of subFrameH loop

        return ddsBuffer;
    }

    public IntBuffer getImagePixelsAsArray(IntBuffer pixels, RPFImageType imageType) throws NITFSRuntimeException {
        RPFFrameFileComponents rpfComponents = this.getRPFFrameFileComponents();
        RPFLocationSection componentLocationTable = rpfComponents.componentLocationTable;

        int spatialDataSubsectionLocation = componentLocationTable.getSpatialDataSubsectionLocation();
        super.buffer.position( spatialDataSubsectionLocation );

        int band = 0; // for(int band = 0; band < rpfComponents.numOfSpectralBandTables;  band++)
        NITFSImageBand imageBand = this.imageBands[band];

        int rgbColor, colorCode;
        short aa, ab, bb;
        short[] codes = new short[(int) this.numOfImageCodesPerRow];
        byte[][] block4x4 = new byte[4][4];
        int rowSize = (short) ((this.numOfImageCodesPerRow * this.imageCodeBitLength) / 8L);
        byte[] rowBytes = new byte[rowSize];
        int subFrameOffset;
        short subFrameIdx = 0;

        for (int subFrameH = 0; subFrameH < this.numOfBlocksPerCol; subFrameH++)
        {
            for (int subFrameW = 0; subFrameW < this.numOfBlocksPerRow; subFrameW++, subFrameIdx++ )
            {
                int blockY = (int) (subFrameH * rpfComponents.numOfOutputRowsPerSubframe);
                int blockX = (int) (subFrameW * rpfComponents.numOfOutputColumnsPerSubframe);

                if(hasMaskedSubframes)
                {
                    subFrameOffset = this.subFrameOffsets[subFrameIdx];
                    if( -1 == subFrameOffset)
                    {   // this is a masked / transparent(?) subframe
                        continue;
                    }
                    else
                    {
                        super.buffer.position( spatialDataSubsectionLocation + subFrameOffset );
                    }
                }

                for (int row = 0; row < this.numOfImageRows; row++)
                {
                    int qy = blockY + row * 4;

                    super.buffer.get(rowBytes, 0, rowSize);

                    // short[] codes = new short[(int) this.numOfImageCodesPerRow];
                    for (int i = 0, cidx = 0, bidx = 0; i < (int) this.numOfImageCodesPerRow / 2; i++)
                    {
                        aa = (short) ((0x00FF & (short) rowBytes[bidx++]) << 4);
                        ab = (short) (0x00FF & (short)  rowBytes[bidx++]);
                        bb = (short) (0x00FF & (short)  rowBytes[bidx++]);

                        codes[cidx++] = (short) (aa | ((0x00F0 & ab) >> 4));
                        codes[cidx++] = (short) (bb | ((0x000F & ab) << 8));
                    }

                    for (int col = 0; col < this.numOfImageCodesPerRow; col++)
                    {
                        this.decompressBlock4x4( block4x4, codes[col] );

                        int qx = blockX + col * 4;

                        for (int h = 0; h < 4; h++)
                        {
                            for (int w = 0; w < 4; w++)
                            {
                                colorCode = 0x00FF & block4x4[h][w];
                                rgbColor = imageBand.lookupRGB(colorCode);
                                switch (imageType)
                                {
                                    case IMAGE_TYPE_ALPHA_RGB:
                                        rgbColor = 0xFF000000 + rgbColor;
                                        break;
//                                    case IMAGE_TYPE_GRAY:
//                                        break;
//                                    case IMAGE_TYPE_RGB:
//                                        break;
                                    case IMAGE_TYPE_GRAY_ALPHA:
                                        rgbColor = (rgbColor << 8) + 0xFF;
                                        break;
                                    case IMAGE_TYPE_RGB_ALPHA:
                                        rgbColor = (rgbColor << 8) + 0xFF;
                                        break;
                                }
//                                pixels[(qy + h) * this.numSignificantCols + (qx + w)] = rgbColor;
                                pixels.put((qy + h) * this.numSignificantCols + (qx + w), rgbColor);
                            }
                        }
                    } // end of column loop
                } // end of row loop
            } // end of subFrameW loop
        } // end of subFrameH loop

        return pixels;
    }

    private void validateImage() throws NITFSRuntimeException {
        RPFFrameFileComponents rpfComponents = this.getRPFFrameFileComponents();

        if(1 != this.compressionAlgorithmID )
            throw new NITFSRuntimeException("NITFSReader.UnsupportedCompressionAlgorithm");
        if( !StringUtil.Equals( this.imageMode, "B"))
            throw new NITFSRuntimeException("NITFSReader.UnsupportedImageMode");
        if( 1 != rpfComponents.numOfSpectralGroups )
            throw new NITFSRuntimeException("NITFSReader.UnsupportedNumberOfSpectralGroups.");
        if( 12 != this.imageCodeBitLength )
            throw new NITFSRuntimeException("NITFSReader.UnsupportedImageCodeBitLength.");



        
    }

    private void parseRPFMaskSubsection(java.nio.ByteBuffer buffer) throws NITFSRuntimeException {
        // parse [ nitf-rpf mask subsection ]
        this.subframeSequenceRecordLength = NITFSUtil.getUShort(buffer);
        this.transparencySequenceRecordLength = NITFSUtil.getUShort(buffer);
        this.transparentOutputPixelCodeLength = NITFSUtil.getUShort(buffer);

        if( 0 != this.transparentOutputPixelCodeLength )
        {
            this.transparentOutputPixelCode = new byte[this.transparentOutputPixelCodeLength];
            buffer.get(this.transparentOutputPixelCode, 0, this.transparentOutputPixelCodeLength);
        }

        if(0 < this.subframeSequenceRecordLength)
        {
            RPFFrameFileComponents rpfComponents = this.getRPFFrameFileComponents();
            subFrameOffsets = new int[ this.numOfBlocksPerCol * this.numOfBlocksPerRow ];
            // parse [ nitf-rpf subframe mask table ]
            int idx = 0;
            for(int group = 0 ; group < rpfComponents.numOfSpectralGroups; group++ )
            {
                for(int row = 0 ; row < this.numOfBlocksPerCol; row++ )
                {
                    for(int col = 0 ; col < this.numOfBlocksPerRow; col++ )
                        subFrameOffsets[idx++] = (int) NITFSUtil.getUInt(buffer);
                }
            }

            hasMaskedSubframes = (null != this.subFrameOffsets && 0 < this.subFrameOffsets.length);
        }
        else
        {
            this.subFrameOffsets = null;
        }
        this.hasMaskedSubframes = (null != this.subFrameOffsets && 0 < this.subFrameOffsets.length);
        this.hasTransparentPixels = (0 < this.transparencySequenceRecordLength);
    }


    private void parseImageData(java.nio.ByteBuffer buffer) throws NITFSRuntimeException {
        RPFLocationSection componentLocationTable = this.getRPFFrameFileComponents().componentLocationTable;

        buffer.position(this.dataStartOffset);
        long spatialDataOffset = NITFSUtil.getUInt(buffer);

        if(0 < componentLocationTable.getMaskSubsectionLength())
        {
            // parse nitf-rpf mask subsection
            buffer.position( componentLocationTable.getMaskSubsectionLocation() );
            this.parseRPFMaskSubsection(buffer);
        }

        if(0 < componentLocationTable.getImageDisplayParametersSubheaderLength())
        {   // parse [ nitf-rpf image display parameter sub-header ]
            buffer.position( componentLocationTable.getImageDisplayParametersSubheaderLocation() );
            this.parseImageDisplayParametersSubheader(buffer);
        }
        else
            throw new NITFSRuntimeException("NITFSReader.ImageDisplayParametersSubheaderNotFound");

        // [ nitf rpf compression section ]
        if(0 < componentLocationTable.getCompressionSectionSubheaderLength())
        {   // parse [ nitf-rpf compression section sub-header ]
            buffer.position( componentLocationTable.getCompressionSectionSubheaderLocation() );
            this.parseRPFCompressionSectionSubheader(buffer);
        }
        else
            throw new NITFSRuntimeException("NITFSReader.RPFCompressionSectionSubheaderNotFound");

        // [ nitf rpf compression lookup sub-section ]
        if(0 < componentLocationTable.getCompressionLookupSubsectionLength())
        {
            buffer.position( componentLocationTable.getCompressionLookupSubsectionLocation() );
            this.parseRPFCompressionLookupSubsection(buffer);
        }
        else
            throw new NITFSRuntimeException("NITFSReader.RPFCompressionLookupSubsectionNotFound");

        // [ nitf rpf compression parameter subsection ]
        if(0 < componentLocationTable.getCompressionParameterSubsectionLength())
            throw new NITFSRuntimeException("NITFSReader.RPFCompressionParameterSubsectionNotImplemented");

        // [ nitf rpf spatial data subsection ]
        if(0 < componentLocationTable.getSpatialDataSubsectionLength())
        {

            buffer.position( componentLocationTable.getSpatialDataSubsectionLocation() );
            this.parseRPFSpatialDataSubsection(buffer);
        }
        else
            throw new NITFSRuntimeException("NITFSReader.RPFSpatialDataSubsectionNotFound");
    }

    private void parseRPFSpatialDataSubsection(java.nio.ByteBuffer buffer) throws NITFSRuntimeException {
        

    }

    private void parseRPFCompressionLookupSubsection(java.nio.ByteBuffer buffer)
        throws NITFSRuntimeException {
        int compressionLookupSubsectionLocation = buffer.position();
        // [ nitf rpf compression lookup sub-section ]
        this.compressionLookupOffsetTableOffset = NITFSUtil.getUInt(buffer);
        this.compressionLookupTableOffsetRecordLength = NITFSUtil.getUShort(buffer);

        this.compressionLUTS = new CompressionLookupRecord[this.numOfCompressionLookupOffsetRecords];
        for(int i = 0 ; i < this.numOfCompressionLookupOffsetRecords; i++)
        {
            this.compressionLUTS[i] = new CompressionLookupRecord( buffer,
                compressionLookupSubsectionLocation,
                this.getRPFFrameFileComponents().rpfColorMaps);
        }
    }

    private void parseRPFCompressionSectionSubheader(java.nio.ByteBuffer buffer) throws NITFSRuntimeException {
        // parse [ nitf-rpf compression section sub-header ]
        this.compressionAlgorithmID = NITFSUtil.getUShort(buffer);
        this.numOfCompressionLookupOffsetRecords = NITFSUtil.getUShort(buffer);
        this.numOfCompressionParameterOffsetRecords = NITFSUtil.getUShort(buffer);
    }

    private void parseImageDisplayParametersSubheader(java.nio.ByteBuffer buffer) throws NITFSRuntimeException {
        // parse [ nitf-rpf image display parameter sub-header ]
        this.numOfImageRows = NITFSUtil.getUInt(buffer);
        this.numOfImageCodesPerRow = NITFSUtil.getUInt(buffer);
        this.imageCodeBitLength = NITFSUtil.getByteAsShort(buffer);
    }
    
    private void parseImageSubheaders(java.nio.ByteBuffer buffer) throws NITFSRuntimeException {
        this.userDefinedSubheaderLength = NITFSUtil.getShortNumeric(buffer, 5);
        if (0 == this.userDefinedSubheaderLength)
        {
            this.userDefSubheader = null;
            return;
        }
        
        this.userDefSubheader = new UserDefinedImageSubheader(buffer);
    }
    private void parseImageLocation(java.nio.ByteBuffer buffer) throws NITFSRuntimeException {
        this.imageRowOffset = NITFSUtil.getShortNumeric(buffer, 5);
        this.imageColOffset = NITFSUtil.getShortNumeric(buffer, 5);
        // [ nitf image magnification ]
        this.imageMagnification = NITFSUtil.getString(buffer, 4);
   }

    private void parseImageTableStructure(java.nio.ByteBuffer buffer) throws NITFSRuntimeException {
        this.imageSyncCode = NITFSUtil.getShortNumeric(buffer, 1);
        this.imageMode = NITFSUtil.getString(buffer, 1);
        this.numOfBlocksPerRow = NITFSUtil.getShortNumeric(buffer, 4);
        this.numOfBlocksPerCol = NITFSUtil.getShortNumeric(buffer, 4);
        this.numOfPixelsPerBlockH = NITFSUtil.getShortNumeric(buffer, 4);
        this.numOfPixelsPerBlockV = NITFSUtil.getShortNumeric(buffer, 4);
        this.numOfBitsPerPixelPerBand = NITFSUtil.getShortNumeric(buffer, 2);
        this.displayLevel = NITFSUtil.getShortNumeric(buffer, 3);
        this.attachmentLevel = NITFSUtil.getShortNumeric(buffer, 3);
    }

    private void parseImageBands(java.nio.ByteBuffer buffer) throws NITFSRuntimeException {
        if(0 == this.NBANDS)
            throw new NITFSRuntimeException("NITFSReader.InvalidNumberOfImageBands");
        this.imageBands = new NITFSImageBand[this.NBANDS];
        for(int i = 0 ; i < this.NBANDS; i++)
            this.imageBands[i] = new NITFSImageBand(buffer);
    }
    private void parseImageCompressionStructure(java.nio.ByteBuffer buffer)
    {
        this.imageCompression = NITFSUtil.getString(buffer, 2);
        this.compressionRateCode = NITFSUtil.getString(buffer, 4);
        this.NBANDS = NITFSUtil.getShortNumeric(buffer, 1);
    }

    private void parseCommentRecords(java.nio.ByteBuffer buffer)
    {
        int numCommentRecords = NITFSUtil.getShortNumeric(buffer, 1);
        if(0 < numCommentRecords)
        {
            this.imageCommentRecords = new String[numCommentRecords];
            for(int i = 0; i < numCommentRecords; i++)
                this.imageCommentRecords[i] = NITFSUtil.getString(buffer, 80);
        }
        else
            this.imageCommentRecords = null;
    }

    private void parseImageGeographicLocation(java.nio.ByteBuffer buffer)
    {
        String hemisphere;
        double deg, min, sec, lat, lon;
        double sixty = 60.0;
        this.imageCoords = new LatLon[4];
        for (int i = 0; i < 4; i++)
        {
            deg = (double) NITFSUtil.getShortNumeric(buffer, 2);
            min = (double) NITFSUtil.getShortNumeric(buffer, 2);
            sec = (double) NITFSUtil.getShortNumeric(buffer, 2);
            hemisphere = NITFSUtil.getString(buffer, 1);
            lat = deg + (min + (sec / sixty)) / sixty;   // deciaml latitude
            if(StringUtil.Equals(hemisphere, "N"))
                lat *= -1.0;

            deg = (double) NITFSUtil.getShortNumeric(buffer, 3);
            min = (double) NITFSUtil.getShortNumeric(buffer, 2);
            sec = (double) NITFSUtil.getShortNumeric(buffer, 2);
            hemisphere = NITFSUtil.getString(buffer, 1);
            lon = deg + (min + (sec / sixty)) / sixty;   // deciaml longitude
            if(StringUtil.Equals(hemisphere, "W"))
                lon *= -1.0;

            // TODO Do not waste time on this calculations - the same info is repeated in the [ rpf coverage section ]
            // TODO zz: garakl: convert to LatLon according to the CoordinateSystem
            // if(0 == StringUtil.compare(imageCoordSystem, "G"))
            this.imageCoords[i] = LatLon.fromDegrees(lat, lon);
        }
    }

    private void parseIdentificationSecurityStructureFields(java.nio.ByteBuffer buffer)
        throws NITFSRuntimeException {
        // [ nitf identification , security, structure fields]
        this.partType = NITFSUtil.getString(buffer, 2);
        if(!StringUtil.Equals("IM", this.partType))
            throw new NITFSRuntimeException("NITFSReader.UnexpectedSegmentType", this.partType);

        this.imageID = NITFSUtil.getString(buffer, 10);
        boolean isSupportedFormat = false;
        for(String s : SupportedFormats)
        {
            if(0 == s.compareTo(this.imageID))
            {
                isSupportedFormat = true;
                break;
            }
        }
        if(!isSupportedFormat)
            throw new NITFSRuntimeException("NITFSReader.UnsupportedImageFormat", this.imageID);

        this.dateTime = NITFSUtil.getString(buffer, 14);
        this.targetID = NITFSUtil.getString(buffer, 17);
        this.imageTitle = NITFSUtil.getString(buffer, 80);
        this.securityClass = NITFSUtil.getString(buffer, 1);
        this.codewords = NITFSUtil.getString(buffer, 40);
        this.controlAndHandling = NITFSUtil.getString(buffer, 40);
        this.releaseInstructions = NITFSUtil.getString(buffer, 40);
        this.classAuthority = NITFSUtil.getString(buffer, 20);              // ISCAUT
        this.securityCtrlNum = NITFSUtil.getString(buffer, 20);             // ISCTLN
        this.ISDWNG = NITFSUtil.getString(buffer, 6);
        this.ISDEVT = StringUtil.Equals(this.ISDWNG, "999998") ? NITFSUtil.getString(buffer, 40) : StringUtil.EMPTY;
        
        this.encryption = NITFSUtil.getShortNumeric(buffer, 1);
        this.imageSource = NITFSUtil.getString(buffer, 42);
        this.numSignificantRows = NITFSUtil.getNumeric(buffer, 8);
        this.numSignificantCols = NITFSUtil.getNumeric(buffer, 8);
        this.pixelValueType = NITFSUtil.getString(buffer, 3);
        this.imageRepresentation = NITFSUtil.getString(buffer, 8);
        this.imageCategory = NITFSUtil.getString(buffer, 8);
        this.bitsPerPixelPerBand = NITFSUtil.getShortNumeric(buffer, 2);
        this.pixelJustification = NITFSUtil.getString(buffer, 1);
        this.imageCoordSystem = NITFSUtil.getString(buffer, 1);
    }

    
}