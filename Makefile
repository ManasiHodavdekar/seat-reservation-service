.PHONY: up down logs build burst burst-smoke

up:
	docker compose up --build -d

down:
	docker compose down -v

logs:
	docker compose logs -f app

build:
	mvn -q -DskipTests package

# BASE_URL defaults to the local docker-compose app; override for a live deploy:
#   make burst BASE_URL=https://your-app.onrender.com
BASE_URL ?= http://localhost:8080

burst:
	./burst.sh $(BASE_URL)

burst-smoke:
	SCALE=0.02 ./burst.sh $(BASE_URL)
