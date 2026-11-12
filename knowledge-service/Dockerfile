FROM docker.m.daocloud.io/library/python:3.11-slim

WORKDIR /app/knowledge-service

# The graph_service resolves project_root = Path(__file__).parents[3]
# (__file__ is in .../knowledge_service/app/services/graph_service.py)
# parents[3] = this WORKDIR. So GRAPH_FILE can be relative here.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY knowledge_service ./knowledge_service
COPY knowledge_graph_v2.json .

EXPOSE 8001

CMD ["uvicorn", "knowledge_service.main:app", "--host", "0.0.0.0", "--port", "8001"]
