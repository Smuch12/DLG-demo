# DLG Privacy Lab

A small Spring Boot + PyTorch demo of the **Deep Leakage from Gradients** (DLG)
attack. You pick a 32x32 target image, the backend computes the true gradient
of a LeNet on that image, and a Python worker tries to *reverse-engineer the
pixels* from that gradient alone — optionally after adding Gaussian noise to
simulate a differential-privacy defence. The UI streams reconstruction frames,
MSE/PSNR, and a loss chart over server-sent events as the attack runs.

Reference: Zhu, Liu, Han, *Deep Leakage from Gradients* (NeurIPS 2019) — <https://arxiv.org/abs/1906.08935>.

## What you need

- **Java 21+** (the Maven wrapper uses it; `./mvnw -v` to check)
- **Python 3.10+** on `PATH` as `python`
- **Python packages:** `torch`, `torchvision`, `pillow`, `numpy`
  (listed in `requirements-dlg.txt`)
- Internet access on first run (torchvision downloads CIFAR-100 into `~/.torch`)

## Install

```bash
# 1. Python deps for the DLG worker
pip install -r requirements-dlg.txt

# 2. Java deps (Maven wrapper fetches what it needs on first run)
./mvnw -DskipTests package
```

On Windows use `mvnw.cmd` instead of `./mvnw`.

## Run

From the project root:

```bash
./mvnw spring-boot:run
```

Then open <http://localhost:8080>. Pick a target, set sigma / iterations, and
click **Run DLG**. Target and reconstruction update live; frames are saved
under `var/dlg/jobs/<job-id>/frames/`.

### Configuration

Defaults in `src/main/resources/application.properties`:

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `dlg.python-command` | `python` | Python interpreter used for the worker |
| `dlg.worker-script` | `scripts/dlg_worker.py` | Worker entry point |
| `dlg.jobs-dir` | `var/dlg/jobs` | Per-job frames / target |
| `dlg.preview-dir` | `var/dlg/previews` | Cached sample previews |

If `python` isn't on your `PATH`, override it:

```bash
./mvnw spring-boot:run -Ddlg.python-command=/full/path/to/python
```

### Running the worker directly (without the web app)

Useful for debugging:

```bash
python scripts/dlg_worker.py \
  --job-dir var/dlg/jobs/manual \
  --sample cifar:25 \
  --sigma 0 \
  --iterations 300 \
  --frame-every 10
```

The worker prints one JSON line per progress event to stdout.

## How it works, end to end

1. Browser POSTs the attack parameters to `/api/dlg/jobs`.
2. `DlgJobService` creates a job directory and hands the request to a
   `PythonDlgWorker`, which spawns `scripts/dlg_worker.py`.
3. The worker loads the target, computes a real gradient on a LeNet, optionally
   adds Gaussian noise, then runs LBFGS to match dummy-image gradients against
   the captured real gradient. Each saved frame + metric is emitted as JSON.
4. Spring forwards those events over SSE to the browser, which updates the
   reconstruction image, metrics, chart, and timeline in real time.
