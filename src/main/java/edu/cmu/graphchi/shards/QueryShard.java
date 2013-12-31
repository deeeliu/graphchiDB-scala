package edu.cmu.graphchi.shards;

import static com.codahale.metrics.MetricRegistry.name;

import com.codahale.metrics.Timer;
import edu.cmu.graphchi.ChiFilenames;
import edu.cmu.graphchi.GraphChiEnvironment;
import edu.cmu.graphchi.VertexInterval;
import edu.cmu.graphchi.preprocessing.VertexIdTranslate;
import edu.cmu.graphchi.queries.QueryCallback;
import scala.actors.threadpool.locks.Lock;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;


import java.io.File;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

/**
 * Shard query support
 * @author Aapo Kyrola
 */
public class QueryShard {

    private ShardIndex index;
    private int shardNum;
    private int numShards;
    private String fileName;
    private File adjFile;

    private LongBuffer adjBuffer;
    private LongBuffer pointerIdxBuffer;
    private IntBuffer inEdgeStartBuffer;
    private VertexInterval interval;


    private static final Timer inEdgeIndexLookupTimer = GraphChiEnvironment.metrics.timer(name(QueryShard.class, "inedge-indexlookup"));

    private final Timer inEdgePhase1Timer = GraphChiEnvironment.metrics.timer(name(QueryShard.class, "inedge-phase1"));
    private final Timer inEdgePhase2Timer = GraphChiEnvironment.metrics.timer(name(QueryShard.class, "inedge-phase2"));

    private int numEdges;

    public QueryShard(String filename, int shardNum, int numShards) throws IOException {
        this(filename, shardNum, numShards, ChiFilenames.loadIntervals(filename, numShards).get(shardNum));
    }

    public final static int BYTES_PER_EDGE = 8;

    public QueryShard(String fileName, int shardNum, int numShards, VertexInterval interval) throws IOException {
        this.shardNum = shardNum;
        this.numShards = numShards;
        this.fileName = fileName;

        this.interval = interval;

        adjFile = new File(ChiFilenames.getFilenameShardsAdj(fileName, shardNum, numShards));
        numEdges = (int) (adjFile.length() / BYTES_PER_EDGE);

        FileChannel channel = new java.io.RandomAccessFile(adjFile, "rw").getChannel();
        adjBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0,
                adjFile.length()).asLongBuffer();
        channel.close();

        index = new ShardIndex(adjFile);
        loadPointers();
        loadInEdgeStartBuffer();
    }

    public boolean isEmpty() {
        return numEdges == 0;
    }

    public long getNumEdges()  {
        return numEdges;
    }

    private void loadInEdgeStartBuffer() throws IOException {
        File inEdgeStartBufferFile = new File(ChiFilenames.getFilenameShardsAdjStartIndices(adjFile.getAbsolutePath()));
        FileChannel inEdgeStartChannel = new java.io.RandomAccessFile(inEdgeStartBufferFile, "r").getChannel();
        inEdgeStartBuffer = inEdgeStartChannel.map(FileChannel.MapMode.READ_ONLY, 0, inEdgeStartBufferFile.length()).asIntBuffer();
        inEdgeStartChannel.close();
    }

    void loadPointers() throws IOException {
        File pointerFile = new File(ChiFilenames.getFilenameShardsAdjPointers(adjFile.getAbsolutePath()));
        FileChannel ptrFileChannel = new java.io.RandomAccessFile(pointerFile, "r").getChannel();
        pointerIdxBuffer = ptrFileChannel.map(FileChannel.MapMode.READ_ONLY, 0, pointerFile.length()).asLongBuffer();
        ptrFileChannel.close();
    }

    // TODO: do not synchronize but make mirror of the buffer
    public Long find(byte edgeType, long src, long dst) {
        final LongBuffer tmpAdjBuffer = adjBuffer.duplicate();
        final LongBuffer tmpPointerBuffer = pointerIdxBuffer.duplicate();
        ShardIndex.IndexEntry indexEntry = index.lookup(src);
        long curPtr = findIdxAndPos(src, indexEntry, tmpPointerBuffer);
        if (curPtr != (-1L)) {
            long nextPtr = tmpPointerBuffer.get();
            int n = (int) (VertexIdTranslate.getAux(nextPtr) - VertexIdTranslate.getAux(curPtr));

            long adjOffset = VertexIdTranslate.getAux(curPtr);
            tmpAdjBuffer.position((int)adjOffset);
            for(int i=0; i<n; i++) {

                // TODO: binary search
                long e = tmpAdjBuffer.get();
                long v = VertexIdTranslate.getVertexId(e);
                if (v == dst && VertexIdTranslate.getType(e) == edgeType) {

                    return PointerUtil.encodePointer(shardNum, (int) adjOffset + i);
                } else if (v > dst) {
                    break;
                }

            }
        }
        return null;
    }

    public boolean deleteEdge(byte edgeType, long src, long dst) {
        Long ptr = find(edgeType, src, dst);
        if (ptr != null) {
            deleteEdgeAtPtr(ptr);
            return true;
        } else {
            return false;
        }
    }

    private void deleteEdgeAtPtr(Long ptr) {
        int idx = PointerUtil.decodeShardPos(ptr);
        long edge = adjBuffer.get(idx);
        adjBuffer.put(idx, VertexIdTranslate.encodeAsDeleted(VertexIdTranslate.getVertexId(edge),
                VertexIdTranslate.getAux(edge)));

        // TODO: delete columns (vardata) --- maybe delete listeners?
    }

    class DeleteCallBack implements QueryCallback {

        @Override
        public boolean immediateReceive() {
            return false;
        }

        @Override
        public void receiveEdge(long src, long dst, byte edgeType, long dataPtr) {
            throw new IllegalStateException();
        }

        @Override
        public void receiveOutNeighbors(long vertexId, ArrayList<Long> neighborIds, ArrayList<Byte> edgeTypes, ArrayList<Long> dataPointers) {
            // A bit wasteful, but not a big deal
            for(long ptr : dataPointers) {
                deleteEdgeAtPtr(ptr);
            }
        }

        @Override
        public void receiveInNeighbors(long vertexId, ArrayList<Long> neighborIds, ArrayList<Byte> edgeTypes, ArrayList<Long> dataPointers) {
            // A bit wasteful, but not a big deal
            for(long ptr : dataPointers) {
                deleteEdgeAtPtr(ptr);
            }
        }
    }

    public synchronized void deleteAllEdgesFor(long vertexId, boolean hasIn, boolean hasOut) {
        if (interval.contains(vertexId) && hasIn) {
            queryIn(vertexId, new DeleteCallBack(), (byte)0, true);
        }
        if (hasOut)
            queryOut(Collections.singleton(vertexId), new DeleteCallBack(), (byte)0, true);
    }


    public void queryOut(Collection<Long> queryIds, QueryCallback callback, byte edgeType) {
        queryOut(queryIds, callback, edgeType, false);
    }

    // TODO: wild-card search
    public void queryOut(Collection<Long> queryIds, QueryCallback callback, byte edgeType, boolean ignoreType) {
        try {
             /* Sort the ids because the index-entries will be in same order */
            ArrayList<Long> sortedIds = new ArrayList<Long>(queryIds);
            Collections.sort(sortedIds);

            ArrayList<ShardIndex.IndexEntry> indexEntries = new ArrayList<ShardIndex.IndexEntry>(sortedIds.size());
            for(Long a : sortedIds) {
                indexEntries.add(index.lookup(a));
            }

            final LongBuffer tmpPointerIdxBuffer = pointerIdxBuffer.duplicate();
            final LongBuffer tmpAdjBuffer = adjBuffer.duplicate();

            ShardIndex.IndexEntry entry = null;
            for(int qIdx=0; qIdx < sortedIds.size(); qIdx++) {
                entry = indexEntries.get(qIdx);
                long vertexId = sortedIds.get(qIdx);
                long curPtr = findIdxAndPos(vertexId, entry, tmpPointerIdxBuffer);

                if (curPtr != (-1L)) {
                    long nextPtr = tmpPointerIdxBuffer.get();
                    int n = (int) (VertexIdTranslate.getAux(nextPtr) - VertexIdTranslate.getAux(curPtr));

                    ArrayList<Long> res = (callback.immediateReceive() ? null : new ArrayList<Long>(n));
                    ArrayList<Long> resPointers = (callback.immediateReceive() ? null :new ArrayList<Long>(n));
                    ArrayList<Byte> resTypes = (callback.immediateReceive() ? null :new ArrayList<Byte>(n));

                    long adjOffset = VertexIdTranslate.getAux(curPtr);
                    tmpAdjBuffer.position((int)adjOffset);
                    for(int i=0; i<n; i++) {
                        long e = tmpAdjBuffer.get();
                        byte etype = VertexIdTranslate.getType(e);

                        if (ignoreType || etype == edgeType) {
                            if (!callback.immediateReceive()) {
                                res.add(VertexIdTranslate.getVertexId(e));
                                resPointers.add(PointerUtil.encodePointer(shardNum, (int) adjOffset + i));
                                resTypes.add(etype);
                            } else {
                                callback.receiveEdge(vertexId, VertexIdTranslate.getVertexId(e),
                                        etype, PointerUtil.encodePointer(shardNum, (int) adjOffset + i));
                            }
                        }
                    }
                    if (!callback.immediateReceive()) callback.receiveOutNeighbors(vertexId, res, resTypes, resPointers);
                } else {
                    if (!callback.immediateReceive()) callback.receiveOutNeighbors(vertexId, new ArrayList<Long>(0), new ArrayList<Byte>(0), new ArrayList<Long>(0));
                }
            }
        } catch (Exception err) {
            throw new RuntimeException(err);
        }
    }


    /**
     * Returns the vertex idx for given vertex in the pointer file, or -1 if not found.
     */
    private long findIdxAndPos(long vertexId, ShardIndex.IndexEntry sparseIndexEntry, final LongBuffer tmpBuffer) {
        assert(sparseIndexEntry.vertex <= vertexId);
        if (tmpBuffer.capacity() == 0) return -1;

        int vertexSeq = sparseIndexEntry.vertexSeq;
        long curvid = sparseIndexEntry.vertex;
        tmpBuffer.position(vertexSeq);
        long ptr = tmpBuffer.get();

        while(curvid <= vertexId) {
            try {
                curvid = VertexIdTranslate.getVertexId(ptr);
                if (curvid == vertexId) {
                    return ptr;
                }
                ptr = tmpBuffer.get();
            } catch (BufferUnderflowException bufe) {
                return -1;
            }
        }
        return -1L;
    }

    public void queryIn(Long queryId, QueryCallback callback, byte edgeType) {
        queryIn(queryId, callback, edgeType, false);
    }

    private void queryIn(Long queryId, QueryCallback callback, byte edgeType, boolean ignoreType) {

        final LongBuffer tmpBuffer = adjBuffer.duplicate();

        if(callback.immediateReceive()) throw new NotImplementedException();

        if (queryId < interval.getFirstVertex() || queryId > interval.getLastVertex()) {
            throw new IllegalArgumentException("Vertex " + queryId + " not part of interval:" + interval);
        }

        try {
            /* Step 1: collect adj file offsets for the in-edges */
            final Timer.Context _timer1 = inEdgePhase1Timer.time();
            ArrayList<Integer> offsets = new ArrayList<Integer>();
            int END = (1<<26) - 1;

            final IntBuffer startBufferTmp = inEdgeStartBuffer.duplicate();

            // Binary search to find the start of the vertex
            int n = inEdgeStartBuffer.capacity() / 2;
            int low = 0;
            int high = n-1;
            int off = -1;
            int queryRelative = (int) (queryId - interval.getFirstVertex());
            while(low <= high) {
                int idx = ((high + low) / 2);
                int v = startBufferTmp.get(idx * 2);
                if (v == queryRelative) {
                    off = startBufferTmp.get(idx * 2 + 1);
                    break;
                }
                if (v < queryRelative) {
                    low = idx + 1;
                } else {
                    high = idx - 1;
                }
            }

            if (off == (-1)) {
                return;
            }

            while(off != END) {
                tmpBuffer.position(off);
                long edge = tmpBuffer.get();
                if (VertexIdTranslate.getVertexId(edge) != queryId) {
                    throw new RuntimeException("Mismatch in edge linkage: " + VertexIdTranslate.getVertexId(edge) + " !=" + queryId);
                }
                if (ignoreType || VertexIdTranslate.getType(edge) == edgeType) {
                    offsets.add(off);
                }

                off = (int) VertexIdTranslate.getAux(edge);

                if (off > END) {
                    throw new RuntimeException("Encoding error: " + edge + " --> " + VertexIdTranslate.getVertexId(edge)
                            + " off : " + VertexIdTranslate.getAux(edge));
                }
                if (off != END && (off < 0 || off > tmpBuffer.capacity())) {
                    System.err.println("Illegal off when looking for inedges: " + off + ", capacity:" + tmpBuffer.capacity() + ", shardNum=" + shardNum);
                }
            }
            _timer1.stop();

            /* Step 2: collect the vertex ids that contain the offsets by passing over the pointer data */
            /* Find beginning */


            ArrayList<Long> inNeighbors = new ArrayList<Long>(offsets.size());
            ArrayList<Long> inNeighborsPtrs = new ArrayList<Long>(offsets.size());
            ArrayList<Byte> edgeTypes = new ArrayList<Byte>(offsets.size());


            final LongBuffer tmpPointerIdxBuffer = pointerIdxBuffer.duplicate();

            Iterator<Integer> offsetIterator = offsets.iterator();
            if (!offsets.isEmpty()) {
                final Timer.Context  _timer2 = inEdgePhase2Timer.time();

                int firstOff = offsets.get(0);
                final Timer.Context  _timer3 = inEdgeIndexLookupTimer.time();
                ShardIndex.IndexEntry startIndex = index.lookupByOffset(firstOff * 8);
                _timer3.stop();

                tmpPointerIdxBuffer.position(startIndex.vertexSeq);

                long last = tmpPointerIdxBuffer.get();
                while (offsetIterator.hasNext()) {
                    off = offsetIterator.next();
                    long ptr = last;

                    while(VertexIdTranslate.getAux(ptr) <= off) {
                        last = ptr;
                        ptr = tmpPointerIdxBuffer.get();
                    }
                    inNeighbors.add(VertexIdTranslate.getVertexId(last));
                    inNeighborsPtrs.add(PointerUtil.encodePointer(shardNum, off));
                    edgeTypes.add(edgeType); // TODO with wild card
                }
                _timer2.stop();

            }
            callback.receiveInNeighbors(queryId, inNeighbors, edgeTypes, inNeighborsPtrs);


        } catch (Exception err) {
            throw new RuntimeException(err);
        }
    }


    public EdgeIterator edgeIterator() {
        final LongBuffer iterBuffer = adjBuffer.duplicate();
        iterBuffer.position(0);
        final LongBuffer iterPointerBuffer = pointerIdxBuffer.duplicate();
        iterPointerBuffer.position(0);


        return new EdgeIterator() {
            int idx = (-1);
            long ptr = (iterPointerBuffer.capacity() > 0 ?  iterPointerBuffer.get() : -1);
            long nextPtr =  (iterPointerBuffer.capacity() > 0 ?  iterPointerBuffer.get() : -1);
            long nextOff = VertexIdTranslate.getAux(nextPtr);
            long curSrc = VertexIdTranslate.getVertexId(ptr);
            long curDst;
            byte curType;
            long vertexPacket;


            @Override
            public boolean hasNext() {
                if (idx < numEdges - 1) {
                    vertexPacket = iterBuffer.get();
                    if (VertexIdTranslate.isEdgeDeleted(vertexPacket)) {
                        next(); // Skip over deleted edges
                        return hasNext();
                    } else {
                        return true;
                    }

                } else {
                    return false;
                }
            }

            @Override
            public void next() {
                idx++;
                if (idx == nextOff) {
                    curSrc = VertexIdTranslate.getVertexId(nextPtr);
                    nextPtr = iterPointerBuffer.get();
                    nextOff = VertexIdTranslate.getAux(nextPtr);
                }

                curDst = VertexIdTranslate.getVertexId(vertexPacket);
                curType = VertexIdTranslate.getType(vertexPacket);
            }

            @Override
            public long getSrc() {
                return curSrc;
            }

            @Override
            public long getDst() {
                return curDst;
            }

            @Override
            public byte getType() {
                return curType;
            }
        };
    }
}
