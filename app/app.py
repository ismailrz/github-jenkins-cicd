from flask import Flask, jsonify, request

app = Flask(__name__)

_items: dict[int, dict] = {}
_next_id = 1


@app.route("/health")
def health():
    return jsonify({"status": "ok"})


@app.route("/items", methods=["GET"])
def list_items():
    return jsonify(list(_items.values()))


@app.route("/items", methods=["POST"])
def create_item():
    global _next_id
    data = request.get_json(silent=True) or {}
    name = data.get("name", "").strip()
    if not name:
        return jsonify({"error": "name is required"}), 400
    item = {"id": _next_id, "name": name}
    _items[_next_id] = item
    _next_id += 1
    return jsonify(item), 201


@app.route("/items/<int:item_id>", methods=["GET"])
def get_item(item_id: int):
    item = _items.get(item_id)
    if item is None:
        return jsonify({"error": "not found"}), 404
    return jsonify(item)


@app.route("/items/<int:item_id>", methods=["DELETE"])
def delete_item(item_id: int):
    if item_id not in _items:
        return jsonify({"error": "not found"}), 404
    del _items[item_id]
    return "", 204


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)  # nosec B104
