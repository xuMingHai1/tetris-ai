"""One independent literal canonical vector; no Java invocation or action execution."""
import hashlib
import unittest


class ReportDescriptorVectorTest(unittest.TestCase):
    def test_literal_canonical_vector(self):
        input_bytes = (b"shape_feasibility_witness,seed,step,piece,actions\n"
                       b"shape_feasibility_witness,7,1,O,HARD_DROP\n")
        declaration = (
            b'{"schema":"fixed-witness-report-request-v1",'
            b'"inputSha256":"92292cb82f47ae608e16fb73e1d47800a022e599905f7dd41a1c20ab8a61ea3b",'
            b'"journalSha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",'
            b'"paths":[{"id":"7","placements":1}],"expected":{"7":{"required":0,'
            b'"forbidden":0,"rawHoles":0,"requiredHoles":0,"forbiddenHoles":0,'
            b'"supportHoles":0,"outsideHoles":0}}}\n')
        self.assertEqual(hashlib.sha256(input_bytes).hexdigest(),
                         "92292cb82f47ae608e16fb73e1d47800a022e599905f7dd41a1c20ab8a61ea3b")
        self.assertEqual(hashlib.sha256(b"").hexdigest(),
                         "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        self.assertEqual(hashlib.sha256(declaration).hexdigest(),
                         "80f7d79d0d8ef9d1c7ee659a48a167ba39696694e5d42335cd130f2881e31b3d")


if __name__ == "__main__":
    unittest.main()
