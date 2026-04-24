import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import torch


ROOT = Path(__file__).resolve().parents[1]
WORKER = ROOT / "scripts" / "dlg_worker.py"

spec = importlib.util.spec_from_file_location("dlg_worker", WORKER)
dlg_worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dlg_worker)


class DlgWorkerTests(unittest.TestCase):
    def test_sigma_zero_leaves_gradients_unchanged(self):
        gradients = [torch.ones(2, 2), torch.arange(3, dtype=torch.float32)]

        noisy = dlg_worker.apply_absolute_noise(gradients, 0)

        for original, updated in zip(gradients, noisy):
            self.assertTrue(torch.equal(original, updated))

    def test_sigma_one_changes_gradients(self):
        torch.manual_seed(1234)
        gradients = [torch.zeros(8, 8)]

        noisy = dlg_worker.apply_absolute_noise(gradients, 1)

        self.assertFalse(torch.equal(gradients[0], noisy[0]))

    def test_three_iteration_smoke_run_emits_progress_and_frame(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [
                    sys.executable,
                    str(WORKER),
                    "--job-dir",
                    directory,
                    "--sample",
                    "synthetic:blocks",
                    "--iterations",
                    "3",
                    "--frame-every",
                    "1",
                    "--sigma",
                    "0",
                    "--seed",
                    "1234",
                    "--device",
                    "cpu",
                    "--lbfgs-max-iter",
                    "1",
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                timeout=120,
                check=True,
            )

            events = [
                json.loads(line)
                for line in result.stdout.splitlines()
                if line.startswith("{")
            ]
            self.assertTrue(any(event["type"] == "target" for event in events))
            self.assertTrue(any(event["type"] == "progress" and event["iteration"] == 3 for event in events))
            self.assertTrue((Path(directory) / "frames" / "000003.png").exists())


if __name__ == "__main__":
    unittest.main()
