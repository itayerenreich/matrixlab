package memory;

public class SharedMatrix {

    private volatile SharedVector[] vectors = {}; // underlying vectors

    public SharedMatrix() {
        vectors = new SharedVector[0];
    }

    public SharedMatrix(double[][] matrix) {
        if (matrix != null) {
            loadRowMajor(matrix);
        }
    }

    public void loadRowMajor(double[][] matrix) {
        if (!isValidMatrix(matrix)) throw new IllegalArgumentException("Matrix must be non-empty and rectangular");
        SharedVector[] newVectors = new SharedVector[matrix.length];
        for (int i = 0; i < newVectors.length; i++) {
            newVectors[i] = new SharedVector(matrix[i], VectorOrientation.ROW_MAJOR);
        }
        this.vectors = newVectors;

    }

    public void loadColumnMajor(double[][] matrix) {
        if (!isValidMatrix(matrix)) throw new IllegalArgumentException("Matrix must be non-empty and rectangular");
        SharedVector[] newVectors = new SharedVector[matrix[0].length];

        for (int i = 0; i < newVectors.length; i++) {
            double[] temp = new double[matrix.length];
            for (int j = 0; j < temp.length; j++) {
                temp[j] = matrix[j][i];
            }
            newVectors[i] = new SharedVector(temp, VectorOrientation.COLUMN_MAJOR);
        }
        this.vectors = newVectors;
    }

    private static boolean isValidMatrix(double[][] matrix) {
        if (matrix == null || matrix.length == 0 || matrix[0] == null) {
            return false;
        }

        int expectedColumns = matrix[0].length;
        if (expectedColumns == 0) {
            return false;
        }

        for (int i = 1; i < matrix.length; i++) {
            if (matrix[i] == null || matrix[i].length != expectedColumns) {
                return false;
            }
        }

        return true;
    }

    public double[][] readRowMajor() {
        SharedVector[] snapshot = this.vectors;
        this.acquireAllVectorReadLocks(snapshot);
        try {
            if (snapshot.length == 0) return new double[0][0];

            // Every vector is read-locked above, so each one is copied as a whole row or column
            // with one lock acquisition, instead of one acquisition per element.
            VectorOrientation matrixOrientation = snapshot[0].getOrientation();
            if (matrixOrientation == VectorOrientation.ROW_MAJOR) {
                double[][] result = new double[snapshot.length][];
                for (int i = 0; i < snapshot.length; i++) {
                    result[i] = snapshot[i].snapshot();
                }
                return result;
            } else {
                int rowLength = snapshot[0].length();
                int colLength = snapshot.length;
                double[][] result = new double[rowLength][colLength];
                for (int j = 0; j < colLength; j++) {
                    double[] column = snapshot[j].snapshot();
                    for (int i = 0; i < rowLength; i++) {
                        result[i][j] = column[i];
                    }
                }
                return result;
            }
        } finally {
            this.releaseAllVectorReadLocks(snapshot);
        }
    }

    public SharedVector get(int index) {
        if (0 > index || index >= vectors.length) {
            throw new RuntimeException("Access to index " + index + " in matrix is not valid.");
        }
        return this.vectors[index];
    }

    public int length() {
        return vectors.length;
    }

    public VectorOrientation getOrientation() {
        return vectors[0].getOrientation();
    }

    private void acquireAllVectorReadLocks(SharedVector[] vecs) {
        for (int i = 0; i < vecs.length; i++) {
            vecs[i].readLock();
        }
    }

    private void releaseAllVectorReadLocks(SharedVector[] vecs) {
        for (int i = 0; i < vecs.length; i++) {
            vecs[i].readUnlock();
        }
    }

    private void acquireAllVectorWriteLocks(SharedVector[] vecs) {
        for (int i = 0; i < vecs.length; i++) {
            vecs[i].writeLock();
        }
    }

    private void releaseAllVectorWriteLocks(SharedVector[] vecs) {
        for (int i = 0; i < vecs.length; i++) {
            vecs[i].writeUnlock();
        }
    }
}
