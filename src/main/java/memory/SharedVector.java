package memory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Mutable vector with owned storage and explicit row/column orientation. */
public class SharedVector {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private final long lockOrder = NEXT_ID.getAndIncrement();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private double[] vector;
    private VectorOrientation orientation;

    public SharedVector(double[] vector, VectorOrientation orientation) {
        if (vector == null || orientation == null) throw new IllegalArgumentException("Vector and orientation are required");
        this.vector = vector.clone();
        this.orientation = orientation;
    }
    public void readLock() { lock.readLock().lock(); }
    public void readUnlock() { lock.readLock().unlock(); }
    public void writeLock() { lock.writeLock().lock(); }
    public void writeUnlock() { lock.writeLock().unlock(); }
    public double get(int index) {
        readLock();
        try {
            if (index < 0 || index >= vector.length) throw new IllegalArgumentException("Illegal index");
            return vector[index];
        } finally { readUnlock(); }
    }
    /**
     * Returns a copy of the whole vector, taken under a single read lock.
     *
     * <p>Copying an entire row at once is much cheaper than calling {@link #get(int)} for every
     * element, which takes and releases the lock once per value. The returned array is owned by
     * the caller: later writes to this vector never change it, and changes to it never reach
     * this vector.
     */
    public double[] snapshot() {
        readLock();
        try { return vector.clone(); }
        finally { readUnlock(); }
    }
    public int length() { readLock(); try { return vector.length; } finally { readUnlock(); } }
    public VectorOrientation getOrientation() { readLock(); try { return orientation; } finally { readUnlock(); } }
    public void transpose() {
        writeLock();
        try { orientation = orientation == VectorOrientation.ROW_MAJOR ? VectorOrientation.COLUMN_MAJOR : VectorOrientation.ROW_MAJOR; }
        finally { writeUnlock(); }
    }
    public void negate() {
        writeLock();
        try { for (int i = 0; i < vector.length; i++) vector[i] = -vector[i]; }
        finally { writeUnlock(); }
    }
    /** Both operands are locked in stable order, including concurrent reciprocal additions. */
    public void add(SharedVector other) {
        if (other == null) throw new IllegalArgumentException("Other vector is required");
        SharedVector first = lockOrder <= other.lockOrder ? this : other;
        SharedVector second = first == this ? other : this;
        first.writeLock(); second.writeLock();
        try {
            if (vector.length != other.vector.length || orientation != other.orientation)
                throw new IllegalArgumentException("Vector dimensions and orientations must match");
            for (int i = 0; i < vector.length; i++) vector[i] += other.vector[i];
        } finally { second.writeUnlock(); first.writeUnlock(); }
    }
    public double dot(SharedVector other) {
        if (other == null) throw new IllegalArgumentException("Other vector is required");
        readLock(); other.readLock();
        try {
            if (orientation == other.orientation || vector.length != other.vector.length)
                throw new IllegalArgumentException("Dot product requires equal lengths and opposite orientations");
            double sum = 0;
            for (int i = 0; i < vector.length; i++) sum += vector[i] * other.vector[i];
            return sum;
        } finally { other.readUnlock(); readUnlock(); }
    }
    public void vecMatMul(SharedMatrix matrix) {
        if (matrix == null) throw new IllegalArgumentException("Matrix is required");
        multiplyByRows(matrix.readRowMajor());
    }
    /** The caller supplies a stable, read-only right-hand snapshot shared across row tasks. */
    public void multiplyByRows(double[][] rows) {
        if (rows == null || rows.length == 0 || rows[0] == null || rows[0].length == 0)
            throw new IllegalArgumentException("Matrix must be non-empty");
        int columns = rows[0].length;
        for (double[] row : rows)
            if (row == null || row.length != columns) throw new IllegalArgumentException("Matrix must be rectangular");
        writeLock();
        try {
            if (vector.length != rows.length) throw new IllegalArgumentException("Multiplication dimensions mismatch");
            double[] output = new double[columns];
            for (int j = 0; j < columns; j++) {
                double sum = 0;
                for (int k = 0; k < vector.length; k++) sum += vector[k] * rows[k][j];
                output[j] = sum;
            }
            vector = output;
        } finally { writeUnlock(); }
    }
}
