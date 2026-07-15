FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# TrueNAS Scale Custom App usually allows mapping internal port to external port. We'll use 30060.
EXPOSE 30060

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "30060"]
