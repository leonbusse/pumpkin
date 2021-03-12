# Pumpkin

Share your Spotify library with friends.

## Local development

### Run project

```
./gradlew :build && docker-compose up --build -d
```

To update single service:

```
./gradlew :build &&
docker-compose build --no-cache api &&
docker-compose up --force-recreate --no-deps -d api
``` 

### Show logs

```
docker-compose logs api
```

## Production

### Tag and push to image registry

Do locally:

```
docker login docker.io 

PUMPKIN_VERSION=0.0.1 ./gradlew :build && 
docker build -t pumpkin-$PUMPKIN_VERSION . &&
docker tag pumpkin-$PUMPKIN_VERSION leonbusse/pumpkin:$PUMPKIN_VERSION &&
docker push leonbusse/pumpkin:$PUMPKIN_VERSION
``` 

### Run on VPS

Then on VPS:
```
docker-compose up -d
```

Deploy current version of service `api`, purging `redis`
```
docker login &&
docker-compose pull api &&
docker-compose down &&
docker-compose rm -f &&
docker-compose up -d redis &&
docker-compose up --force-recreate --no-deps -d api
```


## Setup docker on VPS

```
https://docs.docker.com/engine/install/ubuntu/
```

# Setup HTTPS on VPS

https://www.digitalocean.com/community/tutorials/how-to-install-nginx-on-ubuntu-20-04-de

https://www.digitalocean.com/community/tutorials/how-to-set-up-let-s-encrypt-with-nginx-server-blocks-on-ubuntu-16-04