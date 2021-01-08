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

./gradlew :build && 
docker build -t pumpkin-0.0.1 . &&
docker tag pumpkin-0.0.1 leonbusse/pumpkin:0.0.1 &&
docker push leonbusse/pumpkin:0.0.1
``` 

### Run on VPS

Then on VPS:
```
docker-compose up
```

Or to update and restart single service without affecting others:
```
docker login &&
docker-compose pull api &&
docker-compose up --force-recreate --no-deps -d api
```


## Setup docker on VPS

```
https://docs.docker.com/engine/install/ubuntu/
```

# Setup HTTPS on VPS

https://www.digitalocean.com/community/tutorials/how-to-install-nginx-on-ubuntu-20-04-de

https://www.digitalocean.com/community/tutorials/how-to-set-up-let-s-encrypt-with-nginx-server-blocks-on-ubuntu-16-04