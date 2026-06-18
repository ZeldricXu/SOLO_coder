import { Module } from '@nestjs/common';
import { PrismaModule } from '../prisma/prisma.module';
import { DataSourceController } from './data-source.controller';
import { DataSourceService } from './data-source.service';

@Module({
  imports: [PrismaModule],
  controllers: [DataSourceController],
  providers: [DataSourceService],
  exports: [DataSourceService],
})
export class DataSourceModule {}
