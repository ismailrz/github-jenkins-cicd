def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.get_json()["status"] == "ok"


def test_list_items_empty(client):
    r = client.get("/items")
    assert r.status_code == 200
    assert r.get_json() == []


def test_create_item(client):
    r = client.post("/items", json={"name": "widget"})
    assert r.status_code == 201
    body = r.get_json()
    assert body["name"] == "widget"
    assert "id" in body


def test_create_item_missing_name(client):
    r = client.post("/items", json={})
    assert r.status_code == 400


def test_get_item(client):
    created = client.post("/items", json={"name": "gadget"}).get_json()
    r = client.get(f"/items/{created['id']}")
    assert r.status_code == 200
    assert r.get_json()["name"] == "gadget"


def test_get_item_not_found(client):
    r = client.get("/items/9999")
    assert r.status_code == 404


def test_delete_item(client):
    created = client.post("/items", json={"name": "thing"}).get_json()
    r = client.delete(f"/items/{created['id']}")
    assert r.status_code == 204
    r2 = client.get(f"/items/{created['id']}")
    assert r2.status_code == 404


def test_delete_item_not_found(client):
    r = client.delete("/items/9999")
    assert r.status_code == 404
