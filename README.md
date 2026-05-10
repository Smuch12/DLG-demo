# DLG Privacy Lab

A compact Spring Boot and PyTorch lab that visualizes the **Deep Leakage from Gradients** (DLG) attack. 
The project is a small showcase of my work in *Gradient inversion in decentralized differentially private learning*.

Reference. Zhu, Liu, and Han, *Deep Leakage from Gradients* (NeurIPS 2019). <https://arxiv.org/abs/1906.08935>.

## What you need

- **Java 25**
- **Python 3.10+** on `PATH` as `python`
- **Python packages:** `torch`, `torchvision`, `pillow`, `numpy`
  (listed in `requirements-dlg.txt`)

## Install & Run

```bash
pip install -r requirements-dlg.txt
./mvnw -DskipTests package
./mvnw spring-boot:run
```

Then open <http://localhost:8080>.

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

## How it works, end to end

1. Browser POSTs the attack parameters to `/api/dlg/jobs`.
2. `DlgJobService` creates a job directory and hands the request to a
   `PythonDlgWorker`, which spawns `scripts/dlg_worker.py`.
3. The worker loads the target, computes a real gradient on a LeNet, optionally
   adds Gaussian noise, then runs LBFGS to match dummy-image gradients against
   the captured real gradient. Each saved frame + metric is emitted as JSON.
4. Spring forwards those events over SSE to the browser, which updates the
   reconstruction image, metrics, chart, and timeline in real time.
