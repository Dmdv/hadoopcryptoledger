/**
* Copyright 2016 ZuInnoTe (Jörn Franke) <zuinnote@gmail.com>
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
**/

package org.zuinnote.hadoop.bitcoin.format;


import org.zuinnote.hadoop.bitcoin.format.exception.HadoopCryptoLedgerConfigurationException;
import org.zuinnote.hadoop.bitcoin.format.exception.BitcoinBlockReadException;

import java.io.IOException;
import java.io.InputStream;

import org.apache.hadoop.io.BytesWritable; 

import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Seekable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.io.compress.SplitCompressionInputStream;
import org.apache.hadoop.io.compress.SplittableCompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;

import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.Log;


public class BitcoinTransactionRecordReader implements RecordReader<BytesWritable, BitcoinTransaction> {
private static final Log LOG = LogFactory.getLog(BitcoinBlockRecordReader.class.getName());
private static final String CONF_BUFFERSIZE="io.file.buffer.size";
private static final String CONF_MAXBLOCKSIZE="hadoopcryptoledger.bitcoinblockinputformat.maxblocksize";
private static final String CONF_FILTERMAGIC="hadoopcryptoledger.bitcoinblockinputformat.filter.magic";
private static final String CONF_USEDIRECTBUFFER="hadoopcryptoledeger.bitcoinblockinputformat.usedirectbuffer";
private static final int DEFAULT_BUFFERSIZE=64*1024;
private static final int DEFAULT_MAXSIZE_BITCOINBLOCK=1 * 1024 * 1024;
private static final String DEFAULT_MAGIC = "F9BEB4D9";
private static final boolean DEFAULT_USEDIRECTBUFFER=false;
private int bufferSize=0;
private int maxSizeBitcoinBlock=0; 
private boolean useDirectBuffer=false;
private String specificMagic="";
private String[] specificMagicStringArray;
private byte[][] specificMagicByteArray;

private CompressionCodec codec;
private CompressionCodecFactory compressionCodecs = null;
private Decompressor decompressor;
private Configuration conf;
private long start;
private long pos;
private long end;
private final Seekable filePosition;
private FSDataInputStream fileIn;
private BytesWritable key;
private BitcoinBlock value;
private BitcoinBlockReader bbr;

private int currentTransactionCounterInBlock=0;
private BitcoinBlock currentBitcoinBlock;

/**
* Creates a Record Reader for Bitcoin blocks, ie each Bitcoin block is a record.
* @param split Split to use (assumed to be a file split)
* @param job Configuration:
* io.file.buffer.size: Size of in-memory  specified in the given Configuration. If io.file.buffer.size is not specified the default buffersize (maximum size of a bitcoin block) will be used. The configuration hadoopcryptoledger.bitcoinblockinputformat.filter.magic allows specifying the magic identifier of the block. The magic is a comma-separated list of Hex-values (e.g. F9BEB4D9,FABFB5DA,0B110907,0B110907). The default magic is always F9BEB4D9. One needs to specify at least one magic, otherwise it will be difficult to find blocks in splits. Furthermore, one may specify hadoopcryptoledger.bitcoinblockinputformat.maxblocksize, which defines the maximum size a bitcoin block may have. By default it is 1M). If you want to experiment with performance using DirectByteBuffer instead of HeapByteBuffer you can use "hadoopcryptoledeger.bitcoinblockinputformat.usedirectbuffer" (default: false). Note that it might have some unwanted consequences such as circumwenting Yarn memory management. The option is experimental and might be removed in future versions. 
* @param reporter Reporter
*
*/

public BitcoinTransactionRecordReader(FileSplit split,JobConf job, Reporter reporter) throws IOException,HadoopCryptoLedgerConfigurationException,BitcoinBlockReadException {
    // parse configuration
     this.conf=job;	
	this.maxSizeBitcoinBlock=conf.getInt(this.CONF_MAXBLOCKSIZE,this.DEFAULT_MAXSIZE_BITCOINBLOCK);
	this.bufferSize=conf.getInt(this.CONF_BUFFERSIZE,this.DEFAULT_BUFFERSIZE);
	this.specificMagic=conf.get(this.CONF_FILTERMAGIC);
	// we need to provide at least 
	if ((this.specificMagic==null) || (this.specificMagic.length()==0)) this.specificMagic=this.DEFAULT_MAGIC;
	if ((this.specificMagic!=null) && (this.specificMagic.length()>0)) {
		this.specificMagicStringArray=specificMagic.split(",");
		specificMagicByteArray=new byte[specificMagicStringArray.length][4]; // each magic is always 4 byte
		for (int i=0;i<specificMagicStringArray.length;i++) {
				byte[] currentMagicNo=BitcoinUtil.convertHexStringToByteArray(specificMagicStringArray[i]);
				if (currentMagicNo.length!=4) throw new HadoopCryptoLedgerConfigurationException("Error: Configuration. Magic number has not a length of 4 bytes. Index: "+i);
				specificMagicByteArray[i]=currentMagicNo;
		}
	}	
	this.useDirectBuffer=conf.getBoolean(this.CONF_USEDIRECTBUFFER,this.DEFAULT_USEDIRECTBUFFER);
    // Initialize start and end of split
    start = split.getStart();
    end = start + split.getLength();
    final Path file = split.getPath();
    /** experimental. While a blockchain is not compressed, the user may decide to store it in (any) compressed format in Hadoop **/
    compressionCodecs = new CompressionCodecFactory(job);
    codec = compressionCodecs.getCodec(file);
    final FileSystem fs = file.getFileSystem(job);
    fileIn = fs.open(file);
    // open stream
      if (isCompressedInput()) { // decompress
      	decompressor = CodecPool.getDecompressor(codec);
      	if (codec instanceof SplittableCompressionCodec) {
        	final SplitCompressionInputStream cIn =((SplittableCompressionCodec)codec).createInputStream(fileIn, decompressor, start, end,SplittableCompressionCodec.READ_MODE.BYBLOCK);
		bbr = new BitcoinBlockReader(cIn, this.maxSizeBitcoinBlock,this.bufferSize,this.specificMagicByteArray,this.useDirectBuffer);  
		start = cIn.getAdjustedStart();
       		end = cIn.getAdjustedEnd();
        	filePosition = cIn; // take pos from compressed stream
      } else {
	bbr = new BitcoinBlockReader(codec.createInputStream(fileIn,decompressor), this.maxSizeBitcoinBlock,this.bufferSize,this.specificMagicByteArray,this.useDirectBuffer);
        filePosition = fileIn;
      }
    } else {
      fileIn.seek(start);
      bbr = new BitcoinBlockReader(fileIn, this.maxSizeBitcoinBlock,this.bufferSize,this.specificMagicByteArray,this.useDirectBuffer);  
      filePosition = fileIn;
    }
    // initialize reader
    this.pos=start;
    // seek to block start (for the case a block overlaps a split)
    bbr.seekBlockStart();
}

	

/**
*
* Create an empty key
*
* @return key
*/
public BytesWritable createKey() {
	return new BytesWritable();
}

/**
*
* Create an empty value
*
* @return value
*/
public BitcoinTransaction createValue() {
	return new BitcoinTransaction();
}


/**
*
* Read a next block. 
*
* @param key is a 68 byte array (hashMerkleRoot, prevHashBlock, transActionCounter)
* @param value is a deserialized Java object of class BitcoinBlock
*
* @return true if next block is available, false if not
*/
public boolean next(BytesWritable key, BitcoinTransaction value) throws IOException {
	// read all the blocks, if necessary a block overlapping a split
	while(getFilePosition()<=end) { // did we already went beyond the split (remote) or do we have no further data left?
		if ((currentBitcoinBlock==null) || (currentBitcoinBlock.getTransactions().length==currentTransactionCounterInBlock)){
			try {
				currentBitcoinBlock=bbr.readBlock();
				currentTransactionCounterInBlock=0;
			} catch (BitcoinBlockReadException e) {
				// log
				LOG.error(e);
			}
		}

		if (currentBitcoinBlock==null) return false;

		byte[] hashMerkleRoot=currentBitcoinBlock.getHashMerkleRoot();
		byte[] hashPrevBlock=currentBitcoinBlock.getHashPrevBlock();
		byte[] transactionCounter=BitcoinUtil.convertIntToByteArray(currentTransactionCounterInBlock);
		byte[] newKey=new byte[hashMerkleRoot.length+hashPrevBlock.length+transactionCounter.length];
		for (int i=0;i<hashMerkleRoot.length;i++) {
			newKey[i]=hashMerkleRoot[i];
		}
		for (int j=0;j<hashPrevBlock.length;j++) {
			newKey[j+hashMerkleRoot.length]=hashPrevBlock[j];
		}
		for (int k=0;k<transactionCounter.length;k++) {
			newKey[k+hashMerkleRoot.length+hashPrevBlock.length]=transactionCounter[k];
		}
		key.set(newKey,0,newKey.length);
		value.set(currentBitcoinBlock.getTransactions()[currentTransactionCounterInBlock]);
		currentTransactionCounterInBlock++;
		this.pos=fileIn.getPos();
		return true;
	}
	return false;
}


/**
* Get the current file position in a compressed or uncompressed file.
*
* @return file position
*
*/

private long getFilePosition() throws IOException {
long retVal;
if (isCompressedInput() && null != filePosition) {
      retVal = filePosition.getPos();
} else {
      retVal = pos;
}
  return retVal;
}

/*
* Returns how much of the file has been processed in terms of bytes
*
* @return progress percentage
*
*/

public synchronized float getProgress() throws IOException {
if (start == end) {
      return 0.0f;
    } else {
      return Math.min(1.0f, (getFilePosition() - start) / (float)(end - start));
    }
}

/*
* Determines if the input is compressed or not
*
* @return true if compressed, false if not
*/
private boolean  isCompressedInput() {
    return (codec != null);
  }

/*
* Get current position in the stream
*
* @return position
*
*/

public  synchronized long getPos() throws IOException {
	return pos;
}

/*
* Clean up InputStream and Decompressor after use
*
*
*/

public synchronized void  close() throws IOException {
try {
   if (bbr != null) {
        bbr.close();
      }
    } finally {
      if (decompressor != null) {
        CodecPool.returnDecompressor(decompressor);
        decompressor = null;
      }
    }
  }
}