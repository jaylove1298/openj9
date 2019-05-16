/*[INCLUDE-IF Sidecar18-SE]*/
/*******************************************************************************
 * Copyright (c) 2004, 2017 IBM Corp. and others
 *
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which accompanies this
 * distribution and is available at https://www.eclipse.org/legal/epl-2.0/
 * or the Apache License, Version 2.0 which accompanies this distribution and
 * is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * This Source Code may also be made available under the following
 * Secondary Licenses when the conditions for such availability set
 * forth in the Eclipse Public License, v. 2.0 are satisfied: GNU
 * General Public License, version 2 with the GNU Classpath
 * Exception [1] and GNU General Public License, version 2 with the
 * OpenJDK Assembly Exception [2].
 *
 * [1] https://www.gnu.org/software/classpath/license.html
 * [2] http://openjdk.java.net/legal/assembly-exception.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 OR LicenseRef-GPL-2.0 WITH Assembly-exception
 *******************************************************************************/
package com.ibm.dtfj.java.j9;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

import com.ibm.dtfj.image.CorruptDataException;
import com.ibm.dtfj.image.DataUnavailable;
import com.ibm.dtfj.image.ImagePointer;
import com.ibm.dtfj.image.ImageSection;
import com.ibm.dtfj.image.MemoryAccessException;
import com.ibm.dtfj.image.j9.CorruptData;
import com.ibm.dtfj.java.JavaObject;
import com.ibm.dtfj.java.j9.JavaAbstractClass;

/**
 * @author jmdisher
 *
 */
public class JavaHeapRegion
{
	private JavaHeap _parentHeap;
	private JavaRuntime _javaVM;
//	private String _name;
//	private ImagePointer _id;
	private int _objectAlignment;
	private int _minimumObjectSize;
	private Vector _extents = new Vector();
	private long _arrayletLeafSize;
	
	//this may be null if it was generated by a version of JExtract with missing heap size data
	private HeapSection _section;
	
	
	public JavaHeapRegion(JavaRuntime javaVM, String name, ImagePointer id, int objectAlignment, int minimumObjectSize, long arrayletLeafSize, JavaHeap parentHeap, ImagePointer heapSectionBase, long heapSectionSize)
	{
		_parentHeap = parentHeap;
		_javaVM = javaVM;
		//currently there is no API to access the region name so it has been commented out to eliminate a warning
//		_name = name;
		//currently there is no use for the region id so it has been commented out to eliminate a warning
//		_id = id;
		_objectAlignment = objectAlignment;
		_minimumObjectSize = minimumObjectSize;
		_arrayletLeafSize = arrayletLeafSize;
		if (null != heapSectionBase) {
			_section = new HeapSection(heapSectionBase, heapSectionSize);
		}
	}

	public void addExtent(ImagePointer startAddress, int size, int count)
	{
		_extents.add(new HeapExtent(startAddress, size, count));
	}

	class HeapSection implements ImageSection
	{
		private ImagePointer _base;
		private long _size;
		
		public  HeapSection (ImagePointer base, long size)
		{
			if (null == base) {
				throw new IllegalArgumentException("Heap extents cannot have null base pointers");
			}
			_base = base;
			_size = size;
		}
		
		public ImagePointer getBaseAddress()
		{
			return _base;
		}

		public long getSize()
		{
			return _size;
		}

		public String getName()
		{
			return "Heap extent at 0x" + Long.toHexString(_base.getAddress()) + " (0x" + Long.toHexString(_size) + " bytes)";
		}

		public boolean isExecutable() throws DataUnavailable
		{
			return _base.isExecutable();
		}

		public boolean isReadOnly() throws DataUnavailable
		{
			return _base.isReadOnly();
		}

		public boolean isShared() throws DataUnavailable
		{
			return _base.isShared();
		}
		
		public Properties getProperties() {
			return _base.getProperties();
		}
		
		public boolean equals(Object obj)
		{
			boolean isEqual = false;
			
			if (obj instanceof HeapSection) {
				HeapSection local = (HeapSection) obj;
				isEqual = (_base.equals(local._base) && (_size == local._size));
			}
			return isEqual;
		}
		
		public int hashCode()
		{
			return (int)(_base.hashCode() ^ _size);
		}

	}
	
	
	/**
	 * This extends HeapSection since it needs to implement the ImageSection interface, anyway, and is largely similar.
	 * The reason it needs to implement the ImageSection interface is that there is a bug in a version of JExtract where
	 * the regions do not specify their start and end points so we need to "fake up" the sections using the contiguous
	 * object extents.
	 */
	class HeapExtent extends HeapSection
	{
		private int _residentObjectCount;
		
		public HeapExtent(ImagePointer base, long size, int count)
		{
			super(base, size);
			_residentObjectCount = count;
		}

		public String getName()
		{
			return "Heap Extent at " + Long.toHexString(getBaseAddress().getAddress()) + " (" + getSize() + " bytes long)";
		}
		
		public int objectsInExtent()
		{
			return _residentObjectCount;
		}
		
		public boolean equals(Object obj)
		{
			boolean isEqual = false;
			
			if (obj instanceof HeapExtent) {
				HeapExtent local = (HeapExtent) obj;
				isEqual = (super.equals(local) && (_residentObjectCount == local._residentObjectCount));
			}
			return isEqual;
		}
		
		public int hashCode()
		{
			return (super.hashCode() ^ _residentObjectCount);
		}
	}
	
	class ExtentWalker implements Iterator
	{
		private Iterator _extentIterator;
		private HeapExtent _currentExtent;
		private int _objectsRemainingInExtent;
		private long _nextOffsetInExtent;	//the offset into the current extent which, when added to the base address of said extent, points to the next object header
		private int _alignment;
		private int _minimumObjectSize;
		
		public ExtentWalker(JavaHeap heap, JavaHeapRegion region, JavaRuntime vm, Iterator extents, int alignment, int minimumObjectSize)
		{
			_parentHeap = heap;
			_javaVM = vm;
			_extentIterator = extents;
			_alignment = alignment;
			_minimumObjectSize = minimumObjectSize;
			_advanceExtentMarker();
		}

		/**
		 * Moves the cursor to the next heap extent
		 */
		private void _advanceExtentMarker()
		{
			if (_extentIterator.hasNext()) {
				_currentExtent = (HeapExtent) _extentIterator.next();
				_nextOffsetInExtent=0;
				_objectsRemainingInExtent = _currentExtent.objectsInExtent();
			} else {
				_currentExtent = null;
			}
		}

		public boolean hasNext()
		{
			boolean moreAvailable = false;
			
			if (null != _currentExtent) {
				if (_objectsRemainingInExtent > 0) {
					//note that a comparison of the reading offset and the length could be used here to try to intelligently identify corrupt memory
					moreAvailable = true;
				} else {
					_advanceExtentMarker();
					moreAvailable = (null != _currentExtent);
				}
			}
			return moreAvailable;
		}

		public Object next()
		{
			try {
				//we have to read the pointer at the current marker location and look up the class with that pointer as its ID
				ImagePointer objectAddress = _currentExtent.getBaseAddress().add(_nextOffsetInExtent);
				//then we can construct the JavaObject instance
				JavaObject instance = null;
				try {
					instance = getObjectAtAddress(objectAddress);
				} catch (IllegalArgumentException e) {
					//CMVC 173262 : JavaHeap.getObjectAtAddress() which can throw this exception so handle as corrupt data
					CorruptData cd = new CorruptData("Invalid alignment for JavaObject : should be " + _objectAlignment + " aligned", objectAddress);
					throw new CorruptDataException(cd);
				}
				//the object knows how big it is but it might be fragmented (consider arraylet leaves).  In our implementation, the first
				//image section represents the "inline" component of the object which is what we need to consider for heap walking
				int spaceOccupied = (int)getSpaceOccupied((com.ibm.dtfj.java.j9.JavaObject)instance);

				// J9VM_OPT_NEW_OBJECT_HASH support. If flags show object has moved, add required hashcode space overhead 
				JavaAbstractClass objectClass = (JavaAbstractClass)instance.getJavaClass();
				try {
					int HEADER_HAS_BEEN_MOVED = 0x10000; //#define OBJECT_HEADER_HAS_BEEN_MOVED
					if (_parentHeap.isSWH()) {
						// New value defined in Jazz 16317 for Single Word Header HAS_BEEN_MOVED
						HEADER_HAS_BEEN_MOVED = 0x04;
					}
					int flags = objectClass.readFlagsFromInstance(instance);
					if ((flags & HEADER_HAS_BEEN_MOVED) == HEADER_HAS_BEEN_MOVED) {
						spaceOccupied += objectClass.getHashcodeSlotSize();
					}
				} catch (MemoryAccessException e) {
					throw new CorruptDataException(new CorruptData("Flags in object header unreadable", objectAddress));
				}

				//make sure that we consume at least the minimum object size allowed in one of our heaps
				int spaceConsumed = Math.max(spaceOccupied, _minimumObjectSize);
				int slack = (spaceConsumed % _alignment);
				int filler = (0 == slack) ? 0 : (_alignment - slack);
				spaceConsumed = spaceConsumed + filler;
				_nextOffsetInExtent += spaceConsumed;
				_objectsRemainingInExtent -= 1;
				return instance;
			} catch (CorruptDataException e) {
				//this can just be corrupt
				//note that once we have hit corrupt data, we should probably stop pretending that we can read this heap extent.  Otherwise we tend to get stuck in a loop of bogus memory
				_advanceExtentMarker();
				return e.getCorruptData();
			}
		}
		
		//CMVC 153943 : DTFJ fix for zero-length arraylets - remove code to add 1 to the leaf count in the event that it is 0
		//How zero length arraylets are allocated:
		//Stream		GC					VM					JIT
		//R25_SRT_V2	no leaf pointer		no leaf pointer		leaf pointer
		//R25_WRT_V2	no leaf pointer		no leaf pointer		leaf pointer
		//HEAD   		leaf pointer		leaf pointer		leaf pointer
		//The heap walking code used to call into the JavaObject.getSections() method and then use the first returned section as the
		//size of the entry on the heap. In order to work around the arraylet leaf pointer variances we need to know more about the heap
		//and so a modified version of the JavaObject.getSections() is used which is only interested in determining the heap entry
		//allocation rather than listing all the memory taken up by an object.
		private long getSpaceOccupied(com.ibm.dtfj.java.j9.JavaObject instance) throws CorruptDataException {
			ImagePointer _basePointer = instance.getID();
			//arraylets have a more complicated scheme so handle them differently
			if (instance.isArraylet()) {
				JavaArrayClass arrayForm = (JavaArrayClass) instance.getJavaClass();
				//the first element comes immediately after the header so the offset to it is the size of the header
				// NOTE:  this header size does NOT count arraylet leaves
				int objectHeaderSize = arrayForm.getFirstElementOffset();
				//we require the pointer size in order to walk the leaf pointers in the spine
				int bytesPerPointer = _javaVM.bytesPerPointer();
				try	{
					int instanceSize = arrayForm.getInstanceSize(instance);
					//the instance size will include the header and the actual data inside the array so separate them
					long contentDataSize = (long)(instanceSize - objectHeaderSize);
					//get the number of leaves, excluding the tail leaf (the tail leaf is the final leaf which points back into the spine).  There won't be one if there is isn't a remainder in this calculation since it would be empty
					int fullSizeLeaves = (int)(contentDataSize / _arrayletLeafSize);
					//find out how big the tail leaf would be
					long tailLeafSize = contentDataSize % _arrayletLeafSize;
					//if it is non-zero, we know that there must be one (bear in mind the fact that all arraylets have at least 1 leaf pointer - consider empty arrays)
					int totalLeafCount = (0 == tailLeafSize) ? fullSizeLeaves : (fullSizeLeaves + 1);
					//4-byte object alignment in realtime requires the long and double arraylets have padding which may need to be placed before the array data or after, depending on if the alignment succeeded at a natural boundary or not
					String nestedType = arrayForm.getLeafClass().getName();
					boolean alignmentCandidate = (4 == _objectAlignment) && ("double".equals(nestedType) || "long".equals(nestedType));
					
					long headerAndLeafPointers = -1;		//this is the total size for the headers and leaf pointers
					if(totalLeafCount == 0) {				//there are no leaves present
						if(_objectsRemainingInExtent > 1) {	//need to make sure that there is at least one other object on the heap, otherwise we don't need to peek ahead
							ImagePointer peek = _basePointer.getPointerAt(objectHeaderSize);	//treat the first piece of data following the header as a pointer so automatically account for 32 and 64 bit
							ImagePointer possibleArrayletLeafTarget = null;
							if((bytesPerPointer == 8) || "double".equals(nestedType) || "long".equals(nestedType)) {
								possibleArrayletLeafTarget = _basePointer.getPointerAt(objectHeaderSize + 8);
							} else {
								possibleArrayletLeafTarget = _basePointer.getPointerAt(objectHeaderSize + 4);
							}
							if((peek.getAddress() == possibleArrayletLeafTarget.getAddress()) || (peek.getAddress() == 0)) {		//a zero address indicates a null leaf pointer
								headerAndLeafPointers = objectHeaderSize + bytesPerPointer;		//so add the extra pointer to the reported spine size
							} else {							//a non-zero address is treated as the pointer to the next object on the heap
								headerAndLeafPointers = objectHeaderSize;						//with no leaf it is the same as the object header size
							}
						}
					} else {								// at least one leaf is present
						//we will need a size for the section which includes the spine (and potentially the tail leaf or even all the leaves (in immortal))
						//start with the object header and the leaves
						headerAndLeafPointers = objectHeaderSize + (totalLeafCount * bytesPerPointer);						
					}				

					long spineSectionSize = headerAndLeafPointers;
					//we will now walk the leaves to see if this is an inline arraylet
					//first off, see if we would need padding to align the first inline data element
					long nextExpectedInteriorLeafAddress = _basePointer.getAddress() + headerAndLeafPointers;
					boolean doesHaveTailPadding = false;
					if (alignmentCandidate && (totalLeafCount > 0)) {			//alignment candidates need to have at least 1 leaf otherwise there is nothing to align
						if (0 == (nextExpectedInteriorLeafAddress % 8)) {
							doesHaveTailPadding = true;							//no need to add extra space here so the extra slot will be at the tail
						} else {												//we need to bump up our expected location for alignment
							nextExpectedInteriorLeafAddress += 4;
							spineSectionSize += 4;
							if (0 != (nextExpectedInteriorLeafAddress % 8)) {	//this can't happen so the core is corrupt
								throw new CorruptDataException(new CorruptData("Arraylet leaf pointer misaligned for object", _basePointer));
							}
						}
					}
					for (int i = 0; i < totalLeafCount; i++) {
						ImagePointer leafPointer = _basePointer.getPointerAt(objectHeaderSize + (i * bytesPerPointer));
						if (leafPointer.getAddress() == nextExpectedInteriorLeafAddress) {	//we need to add interior leaves to spine for offset to be correct
							long internalLeafSize = _arrayletLeafSize;						//this pointer is interior so add it to the spine section
							if (fullSizeLeaves == i) {
								internalLeafSize = tailLeafSize;							//this is the last leaf so get the tail leaf size
							}
							spineSectionSize += internalLeafSize;
							nextExpectedInteriorLeafAddress += internalLeafSize;
						}
					}
					if (doesHaveTailPadding) {	//now, add the extra 4 bytes to the end
						spineSectionSize += 4;
					}
					return spineSectionSize;
				} catch (MemoryAccessException e)	{
					//if we had a memory access exception, the spine must be corrupt, or something
					throw new CorruptDataException(new CorruptData("failed to walk arraylet spine", e.getPointer()));
				}
			} else {
				//currently J9 objects are atomic extents of memory but that changes with metronome and that will probably extend to other VM configurations, as well
				long size = ((com.ibm.dtfj.java.j9.JavaAbstractClass)instance.getJavaClass()).getInstanceSize(instance);
				return size;
				// XXX - handle the case of this corrupt data better (may require API change)
			}
		}
		
		public void remove()
		{
			//not allowed (immutable data)
			throw new UnsupportedOperationException();
		}
	}

	public Iterator getSections()
	{
		List sections = null;
		
		//if we have a real section backing this region, just return it.  Otherwise, we will have to fake it by returning the subsections
		if (null != _section) {
			sections = Collections.singletonList(_section);
		} else {
			sections = _extents;
		}
		return sections.iterator();
	}

	public Iterator getObjects()
	{
		return new ExtentWalker(_parentHeap, this, _javaVM, _extents.iterator(), _objectAlignment, _minimumObjectSize);
	}

	public void addNewHeapRegionExtent(long start, long end, int count)
	{
		ImagePointer baseAddress = _javaVM.pointerInAddressSpace(start);
		int size = (int)(end - start);
		addExtent(baseAddress, size, count);
	}

	public int getArrayletSpineSize()
	{
		return _minimumObjectSize;
	}

	public long getArrayletLeafSize()
	{
		return _arrayletLeafSize;
	}
	
	public JavaObject getObjectAtAddress(ImagePointer address) throws CorruptDataException, IllegalArgumentException
	{
		JavaObject object = null;
		
		if ((null != address) && (0 != address.getAddress())) {
			
			//try the special objects cache first...
			JavaObject specialObject = _javaVM.getSpecialObject(address);
			if (null != specialObject) {
				return specialObject;
			}
			
			//CMVC 173262 - check alignment
			if((address.getAddress() & (_objectAlignment - 1)) != 0) {
				throw new IllegalArgumentException("Invalid alignment for JavaObject should be " + _objectAlignment + " aligned. Address = " + address.toString());
			}
			
			long arrayletIdentificationBitmask = 0;
			long arrayletIdentificationResult = 0;
			int arrayletIdentificationWidth = 0;
			int arrayletIdentificationOffset = 0;
			int arrayletSpineSize = 0;
			long arrayletLeafSize = 0;
			
			arrayletIdentificationBitmask = _parentHeap.getArrayletIdentificationBitmask();
			arrayletIdentificationResult = _parentHeap.getArrayletIdentificationResult();
			arrayletIdentificationWidth = _parentHeap.getArrayletIdentificationWidth();
			arrayletIdentificationOffset = _parentHeap.getArrayletIdentificationOffset();
			arrayletSpineSize = getArrayletSpineSize();
			arrayletLeafSize = getArrayletLeafSize();
			
			boolean isArraylet = false;
			if (0 != arrayletIdentificationResult) {
				//note that this may be an arraylet so we need to do some extra work here
				long maskedFlags = 0;
				if (4 == arrayletIdentificationWidth) {
					try {
						maskedFlags = 0xFFFFFFFFL & (long)(address.getIntAt(arrayletIdentificationOffset));
					} catch (MemoryAccessException e) {
						throw new CorruptDataException(new CorruptData("unable to access object flags", address));
					}
				} else if (8 == arrayletIdentificationWidth) {
					try {
						maskedFlags = address.getLongAt(arrayletIdentificationOffset);
					} catch (MemoryAccessException e) {
						throw new CorruptDataException(new CorruptData("unable to access object flags", address));
					}
				} else {
					//this size cannot be read without exposing endian of the underlying core
					System.err.println("Arraylet identification width is invalid: " + arrayletIdentificationWidth + " (should be 4 or 8)");
				}
				isArraylet = arrayletIdentificationResult == (arrayletIdentificationBitmask & maskedFlags);
			}
			object = new com.ibm.dtfj.java.j9.JavaObject(_javaVM, address, _parentHeap, arrayletSpineSize, arrayletLeafSize, isArraylet, _objectAlignment);
		}
		return object;
	}

}
