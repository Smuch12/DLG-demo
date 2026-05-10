import argparse
import json
import math
import random
import sys
from pathlib import Path
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image, ImageDraw


class LeNet(nn.Module):
    def __init__(self, num_classes=100):
        super().__init__()
        act = nn.Sigmoid
        self.body = nn.Sequential(
            nn.Conv2d(3, 12, kernel_size=5, padding=5 // 2, stride=2),
            act(),
            nn.Conv2d(12, 12, kernel_size=5, padding=5 // 2, stride=2),
            act(),
            nn.Conv2d(12, 12, kernel_size=5, padding=5 // 2, stride=1),
            act(),
        )
        self.fc = nn.Sequential(nn.Linear(768, num_classes))

    def forward(self, x):
        out = self.body(x)
        out = out.view(out.size(0), -1)
        return self.fc(out)


def weights_init(module):
    if hasattr(module, "weight") and module.weight is not None:
        module.weight.data.uniform_(-0.5, 0.5)
    if hasattr(module, "bias") and module.bias is not None:
        module.bias.data.uniform_(-0.5, 0.5)


def label_to_onehot(target, num_classes=100):
    target = torch.unsqueeze(target, 1)
    onehot_target = torch.zeros(target.size(0), num_classes, device=target.device)
    onehot_target.scatter_(1, target, 1)
    return onehot_target


def cross_entropy_for_onehot(pred, target):
    return torch.mean(torch.sum(-target * F.log_softmax(pred, dim=-1), 1))


def apply_absolute_noise(gradients, sigma):
    copied = [gradient.detach().clone() for gradient in gradients]
    if sigma == 0:
        return copied
    return [gradient + sigma * torch.randn_like(gradient) for gradient in copied]


def infer_label_from_gradients(gradients):
    final_bias_gradient = gradients[-1].detach().flatten()
    return int(torch.argmin(final_bias_gradient).item())


def emit(payload):
    print(json.dumps(payload, separators=(",", ":")), flush=True)


def pil_to_tensor(image):
    image = image.convert("RGB").resize((32, 32), Image.Resampling.BILINEAR)
    array = np.asarray(image, dtype=np.float32) / 255.0
    return torch.from_numpy(array).permute(2, 0, 1).contiguous()


def save_tensor_png(tensor, path):
    image_tensor = tensor.detach().cpu().clamp(0, 1)
    if image_tensor.dim() == 4:
        image_tensor = image_tensor[0]
    array = (image_tensor.permute(1, 2, 0).numpy() * 255.0).round().astype(np.uint8)
    Image.fromarray(array, mode="RGB").save(path)


def synthetic_sample(sample_id):
    image = Image.new("RGB", (32, 32), (236, 240, 242))
    draw = ImageDraw.Draw(image)

    if sample_id == "synthetic:badge":
        draw.rectangle((0, 0, 31, 8), fill=(22, 119, 132))
        draw.rectangle((0, 24, 31, 31), fill=(214, 84, 64))
        draw.ellipse((8, 7, 24, 23), fill=(250, 198, 95), outline=(42, 48, 59), width=2)
        draw.line((9, 20, 23, 8), fill=(42, 48, 59), width=2)
        label = 7
    elif sample_id == "synthetic:blocks":
        draw.rectangle((3, 3, 14, 14), fill=(31, 132, 90))
        draw.rectangle((18, 4, 28, 14), fill=(222, 108, 58))
        draw.rectangle((7, 18, 25, 28), fill=(69, 105, 180))
        draw.line((0, 31, 31, 0), fill=(245, 204, 92), width=3)
        label = 12
    else:
        draw.rectangle((2, 2, 29, 29), outline=(42, 48, 59), width=2)
        draw.rectangle((6, 6, 16, 26), fill=(41, 128, 185))
        draw.rectangle((17, 10, 26, 26), fill=(230, 126, 34))
        draw.line((5, 27, 27, 5), fill=(39, 174, 96), width=2)
        label = 3

    return image, label, sample_id


def load_target(args):
    if args.image:
        image = Image.open(args.image)
        return image, int(args.seed % 100), "upload"

    if args.sample.startswith("cifar:"):
        index = int(args.sample.split(":", 1)[1])
        try:
            from torchvision import datasets

            root = Path.home() / ".torch"
            dataset = datasets.CIFAR100(str(root), download=True)
            image, label = dataset[index]
            return image, int(label), args.sample
        except Exception as exc:
            emit({
                "type": "message",
                "message": f"CIFAR sample unavailable, using synthetic fallback: {exc}",
            })
            return (*synthetic_sample("synthetic:badge")[:2], "synthetic:badge")

    return synthetic_sample(args.sample)


def metrics(dummy_data, gt_data):
    mse = torch.mean((dummy_data.detach().clamp(0, 1) - gt_data) ** 2).item()
    if mse <= 0:
        psnr = 99.0
    else:
        psnr = -10.0 * math.log10(mse)
    return mse, psnr


def parse_args():
    parser = argparse.ArgumentParser(description="Deep Leakage from Gradients worker.")
    parser.add_argument("--job-dir", required=True)
    parser.add_argument("--image", default="")
    parser.add_argument("--sample", default="cifar:25")
    parser.add_argument("--sigma", type=float, default=0.0)
    parser.add_argument("--iterations", type=int, default=300)
    parser.add_argument("--frame-every", type=int, default=10)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda"])
    parser.add_argument("--lbfgs-max-iter", type=int, default=4)
    parser.add_argument("--label-mode", default="infer", choices=["infer", "optimize"])
    parser.add_argument("--preview-only", action="store_true")
    return parser.parse_args()


def choose_device(requested):
    if requested == "cuda":
        return "cuda"
    if requested == "auto" and torch.cuda.is_available():
        return "cuda"
    return "cpu"


def main():
    args = parse_args()
    job_dir = Path(args.job_dir)
    frame_dir = job_dir / "frames"
    frame_dir.mkdir(parents=True, exist_ok=True)

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    device = choose_device(args.device)

    source_image, label, source = load_target(args)
    gt_data = pil_to_tensor(source_image).to(device).view(1, 3, 32, 32)
    gt_label = torch.tensor([label], dtype=torch.long, device=device)
    gt_onehot_label = label_to_onehot(gt_label)

    target_path = job_dir / "target.png"
    save_tensor_png(gt_data, target_path)
    emit({
        "type": "target",
        "message": "Target image ready.",
        "source": source,
        "label": int(label),
    })
    emit({
        "type": "message",
        "message": f"Running DLG on {device} with torch {torch.__version__}.",
    })
    if args.preview_only:
        emit({
            "type": "done",
            "message": "Target preview complete.",
        })
        return

    net = LeNet().to(device)
    torch.manual_seed(args.seed)
    net.apply(weights_init)
    criterion = cross_entropy_for_onehot

    pred = net(gt_data)
    loss = criterion(pred, gt_onehot_label)
    gradients = torch.autograd.grad(loss, net.parameters())
    original_dy_dx = apply_absolute_noise(gradients, args.sigma)

    dummy_data = torch.randn(gt_data.size(), device=device).requires_grad_(True)
    dummy_label = None
    optimizer_parameters = [dummy_data]
    if args.label_mode == "infer":
        attack_label = torch.tensor([infer_label_from_gradients(original_dy_dx)], dtype=torch.long, device=device)
        attack_onehot_label = label_to_onehot(attack_label)
        emit({
            "type": "message",
            "message": f"Inferred label {int(attack_label.item())} from the shared gradient.",
        })
    else:
        dummy_label = torch.randn(gt_onehot_label.size(), device=device).requires_grad_(True)
        attack_onehot_label = None
        optimizer_parameters.append(dummy_label)

    optimizer = torch.optim.LBFGS(optimizer_parameters,  max_iter=args.lbfgs_max_iter, line_search_fn=None)

    frame_path = frame_dir / "000000.png"
    save_tensor_png(dummy_data, frame_path)
    mse, psnr = metrics(dummy_data, gt_data)
    emit({
        "type": "progress",
        "iteration": 0,
        "loss": None,
        "mse": mse,
        "psnr": psnr,
        "frame": "frames/000000.png",
    })

    last_loss = None
    for iteration in range(1, args.iterations + 1):
        def closure():
            optimizer.zero_grad()
            dummy_pred = net(dummy_data)
            if dummy_label is None:
                dummy_onehot_label = attack_onehot_label
            else:
                dummy_onehot_label = F.softmax(dummy_label, dim=-1)
            dummy_loss = criterion(dummy_pred, dummy_onehot_label)
            dummy_dy_dx = torch.autograd.grad(dummy_loss, net.parameters(), create_graph=True)

            grad_diff = torch.zeros((), device=device)
            for dummy_grad, original_grad in zip(dummy_dy_dx, original_dy_dx):
                grad_diff = grad_diff + ((dummy_grad - original_grad) ** 2).sum()
            grad_diff.backward()
            return grad_diff

        loss_tensor = optimizer.step(closure)
        if loss_tensor is not None:
            last_loss = float(loss_tensor.detach().cpu().item())

        if iteration % args.frame_every == 0 or iteration == args.iterations:
            frame_path = frame_dir / f"{iteration:06d}.png"
            save_tensor_png(dummy_data, frame_path)
            mse, psnr = metrics(dummy_data, gt_data)
            emit({
                "type": "progress",
                "iteration": iteration,
                "loss": last_loss,
                "mse": mse,
                "psnr": psnr,
                "frame": f"frames/{iteration:06d}.png",
            })

    emit({
        "type": "done",
        "iteration": args.iterations,
        "loss": last_loss,
        "message": "DLG run complete.",
    })


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        emit({"type": "error", "message": str(exc)})
        sys.exit(1)