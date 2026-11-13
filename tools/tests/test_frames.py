import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import frames  # noqa: E402

EXACT = frames.Profile(channelThreshold=0, maxChangedFraction=0.0, maxMeanAbsolute=0.0)
LOOSE = frames.Profile(channelThreshold=8, maxChangedFraction=0.01, maxMeanAbsolute=1.0)


def save(directory: Path, name: str, pixels: np.ndarray) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    Image.fromarray(pixels.astype(np.uint8), "RGB").save(directory / name)


def gradient(width: int = 64, height: int = 48) -> np.ndarray:
    x = np.linspace(0, 255, width, dtype=np.uint8)
    return np.stack([np.tile(x, (height, 1))] * 3, axis=2)


class FramesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.reference = self.root / "reference"
        self.candidate = self.root / "candidate"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_identical_frames_pass_the_exact_profile(self) -> None:
        save(self.reference, "a.png", gradient())
        save(self.candidate, "a.png", gradient())
        report = frames.compare(self.reference, self.candidate, EXACT, None)
        self.assertTrue(report["passed"])
        self.assertEqual(report["frames"][0]["maxAbsolute"], 0)

    def test_one_changed_pixel_fails_the_exact_profile(self) -> None:
        changed = gradient()
        changed[10, 10] = [255, 0, 0]
        save(self.reference, "a.png", gradient())
        save(self.candidate, "a.png", changed)
        report = frames.compare(self.reference, self.candidate, EXACT, None)
        self.assertFalse(report["passed"])
        self.assertAlmostEqual(report["frames"][0]["changedFraction"], 1 / (64 * 48))

    def test_small_differences_pass_a_tolerant_profile(self) -> None:
        nudged = gradient().astype(np.int16)
        nudged[:, :, 1] = np.clip(nudged[:, :, 1] + 3, 0, 255)
        save(self.reference, "a.png", gradient())
        save(self.candidate, "a.png", nudged)
        self.assertFalse(frames.compare(self.reference, self.candidate, EXACT, None)["passed"])
        self.assertTrue(frames.compare(self.reference, self.candidate, LOOSE, None)["passed"])

    def test_a_changed_region_fails_a_tolerant_profile(self) -> None:
        changed = gradient()
        changed[:24, :32] = 0
        save(self.reference, "a.png", gradient())
        save(self.candidate, "a.png", changed)
        self.assertFalse(frames.compare(self.reference, self.candidate, LOOSE, None)["passed"])

    def test_missing_extra_and_resized_frames_fail(self) -> None:
        save(self.reference, "a.png", gradient())
        save(self.reference, "b.png", gradient())
        save(self.candidate, "a.png", gradient(32, 24))
        save(self.candidate, "c.png", gradient())
        report = frames.compare(self.reference, self.candidate, LOOSE, None)
        reasons = {frame["frame"]: frame.get("reason", "") for frame in report["frames"]}
        self.assertFalse(report["passed"])
        self.assertIn("sizes differ", reasons["a.png"])
        self.assertEqual(reasons["b.png"], "missing from candidate")
        self.assertEqual(reasons["c.png"], "not in reference")

    def test_no_frames_is_not_a_pass(self) -> None:
        self.reference.mkdir()
        self.candidate.mkdir()
        self.assertFalse(frames.compare(self.reference, self.candidate, EXACT, None)["passed"])

    def test_failed_frames_get_a_difference_image_and_a_report(self) -> None:
        changed = gradient()
        changed[:10, :10] = 0
        save(self.reference, "a.png", gradient())
        save(self.candidate, "a.png", changed)
        out = self.root / "out"
        frames.compare(self.reference, self.candidate, EXACT, out)
        self.assertTrue((out / "a.png").is_file())
        self.assertTrue((out / "report.json").is_file())

    def test_measure_reports_the_worst_difference_across_runs(self) -> None:
        second = gradient()
        second[0, 0] = [0, 200, 0]
        third = gradient()
        third[:4, :4] = 255
        for run, pixels in (("r1", gradient()), ("r2", second), ("r3", third)):
            save(self.root / run, "a.png", pixels)
        report = frames.measure_runs([self.root / "r1", self.root / "r2", self.root / "r3"], 8)
        self.assertEqual(report["pairs"], 2)
        self.assertEqual(report["identicalPairs"], 0)
        self.assertAlmostEqual(report["worst"]["changedFraction"], 16 / (64 * 48))


if __name__ == "__main__":
    unittest.main()
