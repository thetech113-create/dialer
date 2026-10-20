"""Train the offline SMS spam baseline and export the Android inference artifact.

Data source: UCI SMS Spam Collection (CC BY 4.0), downloaded from the UCI archive.
The script intentionally performs no network requests from the Android app.  It uses a
stratified train/validation/held-out split, selects a conservative threshold on validation
data, records held-out metrics, and writes an inspectable JSON artifact consumed on-device.
"""

from __future__ import annotations

import argparse
import io
import json
import re
import urllib.request
import zipfile
from pathlib import Path

import numpy as np
from scipy.sparse import hstack
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import confusion_matrix, f1_score, precision_score, recall_score
from sklearn.model_selection import train_test_split


DATASET_URL = "https://archive.ics.uci.edu/static/public/228/sms+spam+collection.zip"
MODEL_VERSION = "uci_sms_lr_word_char_v1"
RANDOM_SEED = 20261020


def word_features(text: str) -> list[str]:
    tokens = re.findall(r"[a-z0-9]{2,}", text.lower())
    return tokens + [f"{tokens[i]} {tokens[i + 1]}" for i in range(len(tokens) - 1)]


def char_features(text: str) -> list[str]:
    features: list[str] = []
    for word in re.split(r"\s+", text.lower().strip()):
        if not word:
            continue
        padded = f" {word} "
        for size in (3, 4, 5):
            features.extend(padded[i : i + size] for i in range(len(padded) - size + 1))
    return features


def download_dataset() -> tuple[list[str], np.ndarray]:
    with urllib.request.urlopen(DATASET_URL, timeout=30) as response:
        archive = zipfile.ZipFile(io.BytesIO(response.read()))
    raw = archive.read("SMSSpamCollection").decode("utf-8", errors="replace")
    labels, texts = [], []
    for line in raw.splitlines():
        label, separator, text = line.partition("\t")
        if separator and label in {"ham", "spam"}:
            labels.append(label)
            texts.append(text)
    return texts, np.asarray([1 if value == "spam" else 0 for value in labels])


def metrics(y_true: np.ndarray, probabilities: np.ndarray, threshold: float) -> dict[str, object]:
    predicted = (probabilities >= threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y_true, predicted, labels=[0, 1]).ravel()
    return {
        "threshold": round(float(threshold), 4),
        "precision": round(float(precision_score(y_true, predicted, zero_division=0)), 4),
        "recall": round(float(recall_score(y_true, predicted, zero_division=0)), 4),
        "f1": round(float(f1_score(y_true, predicted, zero_division=0)), 4),
        "false_positive_rate": round(float(fp / (fp + tn)) if fp + tn else 0.0, 4),
        "confusion_matrix": [[int(tn), int(fp)], [int(fn), int(tp)]],
    }


def pick_threshold(y_true: np.ndarray, probabilities: np.ndarray) -> float:
    # Prefer precision >= 0.90, then the best recall. This keeps automatic warning conservative.
    candidates = np.arange(0.30, 0.91, 0.01)
    eligible = [t for t in candidates if precision_score(y_true, probabilities >= t, zero_division=0) >= 0.90]
    return float(max(eligible, key=lambda t: (recall_score(y_true, probabilities >= t, zero_division=0), -t)))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    texts, labels = download_dataset()
    x_train, x_temp, y_train, y_temp = train_test_split(
        texts, labels, test_size=0.30, stratify=labels, random_state=RANDOM_SEED
    )
    x_validation, x_test, y_validation, y_test = train_test_split(
        x_temp, y_temp, test_size=0.50, stratify=y_temp, random_state=RANDOM_SEED
    )

    word = TfidfVectorizer(analyzer=word_features, min_df=2, max_features=1800, sublinear_tf=True, norm="l2")
    char = TfidfVectorizer(analyzer=char_features, min_df=2, max_features=1800, sublinear_tf=True, norm="l2")
    train_matrix = hstack([word.fit_transform(x_train), char.fit_transform(x_train)])
    validation_matrix = hstack([word.transform(x_validation), char.transform(x_validation)])
    test_matrix = hstack([word.transform(x_test), char.transform(x_test)])

    classifier = LogisticRegression(C=2.0, class_weight="balanced", max_iter=2000, random_state=RANDOM_SEED)
    classifier.fit(train_matrix, y_train)
    validation_probabilities = classifier.predict_proba(validation_matrix)[:, 1]
    suspicious_threshold = pick_threshold(y_validation, validation_probabilities)
    high_confidence_threshold = max(0.85, suspicious_threshold)
    test_probabilities = classifier.predict_proba(test_matrix)[:, 1]

    coefficients = classifier.coef_[0]
    word_count = len(word.vocabulary_)

    def export_features(vectorizer: TfidfVectorizer, offset: int) -> dict[str, list[float]]:
        return {
            term: [round(float(vectorizer.idf_[index]), 8), round(float(coefficients[offset + index]), 8)]
            for term, index in vectorizer.vocabulary_.items()
        }

    artifact = {
        "model_version": MODEL_VERSION,
        "model_type": "TF-IDF logistic regression (word 1-2 gram + character 3-5 gram)",
        "dataset": {"name": "UCI SMS Spam Collection", "url": DATASET_URL, "records": len(texts), "spam_records": int(labels.sum())},
        "split": {"seed": RANDOM_SEED, "train": len(x_train), "validation": len(x_validation), "test": len(x_test)},
        "training": {"class_weight": "balanced", "C": 2.0, "min_df": 2, "word_max_features": 1800, "char_max_features": 1800},
        "intercept": round(float(classifier.intercept_[0]), 8),
        "thresholds": {"suspicious": round(suspicious_threshold, 4), "high_confidence": round(high_confidence_threshold, 4)},
        "validation_metrics": metrics(y_validation, validation_probabilities, suspicious_threshold),
        "held_out_test_metrics": metrics(y_test, test_probabilities, suspicious_threshold),
        "word_features": export_features(word, 0),
        "char_features": export_features(char, word_count),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(artifact, separators=(",", ":"), sort_keys=True), encoding="utf-8")
    print(json.dumps({"model_version": MODEL_VERSION, "held_out_test_metrics": artifact["held_out_test_metrics"]}, indent=2))


if __name__ == "__main__":
    main()
